package com.we.meet.livekit

import android.content.Context
import android.content.Intent
import com.we.meet.audio.CallAudioDeviceModule
import com.we.meet.audio.CallFocusAudioHandler
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.events.EventListenable
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.datastream.TextStreamInfo
import io.livekit.android.room.datastream.incoming.TextStreamHandler
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import org.json.JSONObject

/**
 * Thin imperative wrapper around the LiveKit Android SDK.
 *
 * Owns one [Room] instance and exposes the small surface the [com.we.meet.ui.room.RoomViewModel]
 * needs: connect, disconnect, mic toggle, camera toggle, switch camera, plus
 * the underlying event flow.
 *
 * Mirrors the web client's settings ([adaptiveStream], [dynacast]).
 *
 * Audio routing: we replace the default [io.livekit.android.audio.AudioSwitchHandler]
 * with a [CallFocusAudioHandler] that only manages audio focus + communication mode,
 * and we bring our own [CallAudioDeviceModule] so we have a handle on the
 * underlying WebRTC `AudioTrack` — on Android 15/16 we need to call
 * `AudioTrack.setPreferredDevice` directly to hot-reroute an already-playing
 * stream. Routing is driven by [com.we.meet.audio.AudioOutputController]
 * (see that class for the reasoning).
 */
class LiveKitController(
    appContext: Context,
    /**
     * Codec to publish camera video with. Captured at construction — the SDK
     * reads it once when the local track is created, so a setting change only
     * takes effect on the next meeting (next [LiveKitController] instance).
     */
    videoCodec: VideoCodec = VideoCodec.H264,
) {

    /**
     * Exposed so [com.we.meet.audio.AudioOutputController] can pin the
     * playback route via [CallAudioDeviceModule.setPreferredDevice].
     */
    val callAudioDeviceModule: CallAudioDeviceModule =
        CallAudioDeviceModule(appContext.applicationContext)

    val room: Room = LiveKit.create(
        appContext = appContext.applicationContext,
        options = RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                videoCodec = videoCodec.codecName,
            ),
        ),
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                audioHandler = CallFocusAudioHandler(appContext.applicationContext),
                // Provide our own AudioDeviceModule so (a) we can build it
                // with useLowLatency=false — LOW_LATENCY AudioTracks on
                // Android 15+ are pinned to the speaker fast-path and ignore
                // `setCommunicationDevice` — and (b) we can reach into it
                // and call `setPreferredDevice` to hot-reroute the live
                // AudioTrack when the user toggles speaker/earpiece mid-call.
                audioDeviceModule = callAudioDeviceModule.module,
            ),
        ),
    )

    val events: EventListenable<RoomEvent> get() = room.events

    /**
     * Connect to the LiveKit room and, if requested, capture+publish mic
     * and camera during the connect flow.
     *
     * When [audio] / [video] are true here, the SDK schedules publish
     * inside its own connect state machine — that's important when the
     * App is the *first* connector to a freshly-created room: a separate
     * `setMicrophoneEnabled(true)` immediately after `connect()` races
     * with LiveKit server-side room initialization and the publish can
     * silently fail (returns false, no throw). Doing it via ConnectOptions
     * avoids that race for the creator path. The post-connect
     * setMicrophoneEnabled / setCameraEnabled the caller still runs is
     * a no-op fallback (publication exists → just toggles muted).
     */
    suspend fun connect(
        url: String,
        token: String,
        audio: Boolean = false,
        video: Boolean = false,
    ) {
        room.connect(
            url = url,
            token = token,
            options = ConnectOptions(
                autoSubscribe = true,
                audio = audio,
                video = video,
            ),
        )
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        val ok = room.localParticipant.setMicrophoneEnabled(enabled)
        if (enabled && !ok) {
            android.util.Log.w(TAG, "setMicrophoneEnabled(true) returned false — publish likely failed")
        }
        return ok
    }

    suspend fun setCameraEnabled(enabled: Boolean): Boolean {
        val ok = room.localParticipant.setCameraEnabled(enabled)
        if (enabled && !ok) {
            android.util.Log.w(TAG, "setCameraEnabled(true) returned false — publish likely failed")
        }
        return ok
    }

    /**
     * Best-effort camera flip.  The LiveKit camera capturer exposes a switch
     * helper through the track's capturer; if the underlying capturer is not
     * a multi-camera capturer (e.g. emulator with a single virtual webcam),
     * the call is silently a no-op.
     */
    fun switchCamera() {
        val pub = room.localParticipant.getTrackPublication(Track.Source.CAMERA) ?: return
        val track = pub.track as? LocalVideoTrack ?: return
        runCatching { track.switchCamera() }
    }

    /**
     * Start or stop the local screen-share publication.
     *
     * When enabling, [mediaProjectionResultData] must be the Intent returned
     * from [android.media.projection.MediaProjectionManager.createScreenCaptureIntent].
     * The SDK binds its own [io.livekit.android.room.track.screencapture.ScreenCaptureService]
     * (declared in our manifest with `foregroundServiceType="mediaProjection"`)
     * for the lifetime of the capture — the OS requires a mediaProjection FGS
     * to be running before createVirtualDisplay on API 34+.
     *
     * [onSystemStop] fires when MediaProjection is revoked outside our control
     * (user taps the system's "stop sharing" notification, or the session
     * dies) — RoomViewModel uses this to reconcile UI state.
     */
    suspend fun setScreenShareEnabled(
        enabled: Boolean,
        mediaProjectionResultData: Intent? = null,
        onSystemStop: (() -> Unit)? = null,
    ): Boolean {
        val params = if (enabled && mediaProjectionResultData != null) {
            ScreenCaptureParams(
                mediaProjectionPermissionResultData = mediaProjectionResultData,
                onStop = onSystemStop,
            )
        } else {
            null
        }
        return room.localParticipant.setScreenShareEnabled(enabled, params)
    }

    fun disconnect() {
        room.disconnect()
    }

    fun release() {
        runCatching { room.unregisterTextStreamHandler(CHAT_TOPIC) }
        runCatching { room.release() }
        // When we provide our own AudioDeviceModule via LiveKitOverrides, the
        // SDK leaves ownership with us (see AudioOptions.audioDeviceModule
        // docs) — so we must release it ourselves to avoid a leak.
        callAudioDeviceModule.release()
    }

    // ── In-meeting chat ──────────────────────────────────────────────────
    //
    // Mirror @livekit/components-core's `setupChat`: send/receive over BOTH
    //   1. Text Streams on topic `lk.chat`         (requires server v1.8.2+)
    //   2. Legacy DataChannel on topic `lk-chat-topic` (JSON payload)
    //
    // Production we-meet is currently on livekit-server v1.7.2 which has no
    // Text-Stream support; the web client therefore relies on (2). We must
    // mirror that path or Android stays mute in both directions. (1) is kept
    // for forward compatibility once the server is upgraded.
    //
    // Receivers MUST de-duplicate by message id across the two channels and
    // honour `ignoreLegacy=true` on incoming legacy packets (set by senders
    // that already published the same message via Text Streams).

    suspend fun sendChatText(text: String): Result<TextStreamInfo> =
        room.localParticipant.sendText(text, StreamTextOptions(topic = CHAT_TOPIC))

    fun registerChatHandler(handler: TextStreamHandler) {
        runCatching { room.registerTextStreamHandler(CHAT_TOPIC, handler) }
    }

    /**
     * Publish a chat message via the legacy DataChannel topic
     * (`lk-chat-topic`). Payload schema matches what `@livekit/components-core`
     * encodes — `{ id, timestamp, message, ignoreLegacy }`. Set
     * [ignoreLegacy] to true when the same message was also (successfully)
     * sent via Text Streams so 1.8.2+ web peers don't double-render it.
     */
    suspend fun sendChatLegacy(
        id: String,
        text: String,
        timestampMs: Long,
        ignoreLegacy: Boolean,
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("id", id)
            put("timestamp", timestampMs)
            put("message", text)
            put("ignoreLegacy", ignoreLegacy)
        }.toString().toByteArray(Charsets.UTF_8)
        return room.localParticipant.publishData(
            data = payload,
            reliability = DataPublishReliability.RELIABLE,
            topic = LEGACY_CHAT_TOPIC,
        )
    }

    companion object {
        private const val TAG = "LiveKitController"
        const val CHAT_TOPIC = "lk.chat"
        const val LEGACY_CHAT_TOPIC = "lk-chat-topic"
    }
}
