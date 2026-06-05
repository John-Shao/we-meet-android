package com.we.meet.ui.room

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.WaitingParticipantDto
import com.we.meet.data.chat.ChatMessageUi
import com.we.meet.data.history.HistoryStore
import com.we.meet.data.repository.RoomRepository
import com.we.meet.livekit.LiveKitController
import com.we.meet.service.ConferenceForegroundService
import com.we.meet.util.toUserMessage
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

private const val TAG = "RoomViewModel"

/**
 * LiveKit participant attribute key the backend writes when a user raises
 * or lowers their hand (set by `POST /toggle-hand/`). Empty string means
 * the hand is down; a non-empty ISO 8601 timestamp means the hand is up.
 */
private const val HAND_RAISED_ATTRIBUTE = "handRaisedAt"

/**
 * Max subtitle segments kept in memory. A 4-hour all-hands at one
 * segment/second is ~14k entries — multiply by ~200 bytes per item
 * (id, name, text snapshot) and that's ~3MB, fine for the runtime.
 * Cap exists so a runaway meeting doesn't pile heap indefinitely.
 */
private const val SUBTITLE_BUFFER_MAX = 2000

/** UI-facing snapshot of one participant in the room. */
data class ParticipantUi(
    val identity: String,
    val name: String,
    val isLocal: Boolean,
    val isMicEnabled: Boolean,
    val videoTrack: VideoTrack?,
    val isSpeaking: Boolean = false,
    /**
     * True when this tile represents a screen-share publication rather than
     * a participant's camera. We emit a *separate* [ParticipantUi] for each
     * screen-share track (identity suffixed with [SCREEN_SHARE_ID_SUFFIX]) so
     * the gallery shows both the sharer's camera and their shared screen as
     * distinct tiles, mirroring Tencent-Meeting-style behaviour.
     */
    val isScreenShare: Boolean = false,
    /**
     * Raised-hand state, mirrored from the backend `handRaisedAt`
     * participant attribute. `null` or empty → hand is down; an ISO 8601
     * timestamp → hand is up (the timestamp is the raise instant, used
     * for queue ordering — earliest-raised goes first).
     */
    val handRaisedAt: String? = null,
) {
    companion object {
        const val SCREEN_SHARE_ID_SUFFIX = "#screen"
    }
}

/** UI-facing snapshot of the room. */
data class RoomUiState(
    val phase: Phase = Phase.Connecting,
    val participants: List<ParticipantUi> = emptyList(),
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val errorMessage: String? = null,
    /** Identity of the participant pinned to the focus (big) tile; null = Gallery mode. */
    val focusIdentity: String? = null,
    /**
     * True when we were disconnected because the host ended the meeting
     * (server pushed [DisconnectReason.ROOM_DELETED] / [DisconnectReason.ROOM_CLOSED])
     * rather than because the local user chose to leave. Drives the
     * auto-leave + "host ended" sheet on Home.
     */
    val hostEnded: Boolean = false,
    /**
     * Incremented on every successful (re)connect. Used as part of the
     * Compose `key` for participant tiles so they tear down and re-mount
     * across a reconnect — otherwise VideoTrackView keeps its pre-reconnect
     * SurfaceView bound to the old RTCVideoTrack, and remote video freezes
     * on its last frame even though fresh publications are subscribed.
     */
    val sessionGeneration: Int = 0,
    /** In-meeting chat messages (oldest first). Ephemeral — cleared on disconnect. */
    val messages: List<ChatMessageUi> = emptyList(),
    /**
     * True while the local participant is publishing a screen-share track.
     * Drives the Share / Stop Share state in the More sheet.
     */
    val localScreenSharing: Boolean = false,
    /**
     * True if this user can administer the room (owner or admin role).
     * Sourced from `RoomDto.is_administrable` at preview time and frozen
     * for the session — role changes mid-meeting are not modelled. Exposed
     * here so any in-room Composable can gate host-only UI (kick / mute /
     * rename / waiting-list admit) without threading isAdmin through new
     * function signatures.
     */
    val isAdmin: Boolean = false,
    /**
     * Lobby snapshot — non-empty only when the host has visitors waiting
     * to be admitted. Driven by a background poll loop in the ViewModel
     * (started after Connected, only for isAdmin=true). Empty list for
     * non-admins and for empty lobbies.
     */
    val waitingParticipants: List<WaitingParticipantDto> = emptyList(),
    /**
     * Current room access level — one of "public" / "trusted" /
     * "restricted", or null until first loaded. Owner-only host
     * settings sheet writes this. Non-owners are gated upstream from
     * mutating it, but the field is also visible so a future "viewer
     * sees the room's policy" UI doesn't need new plumbing.
     */
    val accessLevel: String? = null,
    /**
     * True while the LiveKit room broadcasts `isRecording = true` (set
     * by the backend when a Recording row is ACTIVE). Drives the red
     * "正在录制" banner above the participant grid and flips the More
     * sheet's record button to "stop". Updated from
     * [RoomEvent.RecordingStatusChanged] — visible to ALL participants,
     * not just the host who started it.
     */
    val isRecording: Boolean = false,
    /**
     * True between tapping start/stop and the server's RecordingStatus
     * event landing. Used to disable the record button so the host
     * can't double-fire start/stop while the worker spins up.
     */
    val recordingPending: Boolean = false,
    /**
     * Whether the subtitles overlay is currently visible on this device.
     * Independent of [subtitlesStartedOnBackend] — overlay is a local
     * view toggle that the user can flip without sending another POST.
     * Driven by [RoomViewModel.toggleSubtitles].
     */
    val subtitlesOverlayOn: Boolean = false,
    /**
     * True while the start-subtitle POST is in flight. Disables the
     * subtitle button so a fast double-tap can't fire two requests.
     */
    val subtitlesPending: Boolean = false,
) {
    enum class Phase { Connecting, Connected, Error, Disconnected }
}

/**
 * A single transcription line emitted by the LiveKit agent. We keep
 * what the UI actually needs:
 *
 *  * [id] — SDK-provided segment id, used to dedup partial vs final
 *    pushes from STT (we only keep the first per id, matching Web).
 *  * [participantIdentity] — speaker identity (stable across renames)
 *  * [participantName] — speaker display name at the moment the
 *    segment landed (we snapshot it so renames don't rewrite history)
 *  * [text] — the recognized text
 *  * [timestamp] — firstReceivedTime from the SDK (ms epoch)
 */
data class SubtitleSegment(
    val id: String,
    val participantIdentity: String,
    val participantName: String,
    val text: String,
    val timestamp: Long,
)

class RoomViewModel(
    application: Application,
    private val roomId: String,
    private val livekitUrl: String,
    private val livekitToken: String,
    private val roomName: String,
    private val roomSlug: String,
    private val roomRepository: RoomRepository,
    private val historyStore: HistoryStore,
    private val selfName: String,
    private val host: String?,
    private val createdAtMs: Long,
    private val initialMicEnabled: Boolean = true,
    private val initialCameraEnabled: Boolean = true,
    private val isAdmin: Boolean = false,
) : AndroidViewModel(application) {

    private val controller = LiveKitController(
        appContext = application,
        videoCodec = (application as WeMeetApp).settingsStore.videoCodec.value.sdkCodec,
    )

    val room: Room get() = controller.room

    /** Exposed so UI can pin the live WebRTC AudioTrack to an output device. */
    val callAudioDeviceModule get() = controller.callAudioDeviceModule

    // Seed mic/camera with the user's Preview-time intent. Otherwise the
    // default (true) races with connect(): refreshParticipants() runs the
    // instant we go Connected, but `local.isCameraEnabled` stays false until
    // the TrackPublished event lands — so the toolbar button briefly shows
    // OFF even though the camera is coming up.
    private val _state = MutableStateFlow(
        RoomUiState(
            micEnabled = initialMicEnabled,
            cameraEnabled = initialCameraEnabled,
            isAdmin = isAdmin,
        ),
    )
    val state: StateFlow<RoomUiState> = _state.asStateFlow()

    /**
     * Subtitle segments accumulated since the agent started pushing
     * transcriptions. Kept on its own StateFlow so the high-frequency
     * append from TranscriptionReceived doesn't bounce the full
     * RoomUiState — the participants tile + toolbars don't depend on it.
     */
    private val _subtitleSegments = MutableStateFlow<List<SubtitleSegment>>(emptyList())
    val subtitleSegments: StateFlow<List<SubtitleSegment>> = _subtitleSegments.asStateFlow()

    /**
     * One-shot guard so a host (or anyone with subtitle-start permission)
     * tapping the button twice in a row doesn't kick off two backend
     * agents. The flag stays true for the rest of the session — there's
     * no stop-subtitle endpoint.
     */
    private var subtitlesStartedOnBackend = false

    /**
     * Identities of participants currently detected as active speakers by
     * LiveKit. Kept separately so refreshParticipants() can merge it into
     * ParticipantUi without depending on timing of the event.
     */
    private var activeSpeakerIds: Set<String> = emptySet()

    /**
     * Stable ordering of identities. Once a participant enters the list we
     * keep their position; newcomers are appended. This avoids the whole
     * gallery reshuffling when tracks re-publish.
     */
    private val orderedIdentities = LinkedHashSet<String>()

    /**
     * Set to true when the local user initiates leaving (Leave button or
     * host-only End meeting). Used to distinguish "host of this meeting
     * ended it" from "a *different* host ended the meeting out from under
     * me" — the server pushes ROOM_DELETED to everyone including the
     * acting host, and we don't want the acting host to see the
     * "host ended" sheet on themselves.
     */
    private var userInitiatedLeave = false

    /**
     * Handle to the active lobby-poll job so we can cancel + restart it
     * across reconnects. Owner-only: never started when [isAdmin]=false.
     * Null while idle.
     */
    private var lobbyPollJob: kotlinx.coroutines.Job? = null

    init {
        observeEvents()
        connect()
    }

    private fun connect() {
        viewModelScope.launch {
            runCatching {
                controller.connect(
                    livekitUrl, livekitToken,
                    audio = initialMicEnabled,
                    video = initialCameraEnabled,
                )
                // Defensive: if the in-connect publish silently failed (Boolean
                // return, no throw — see LiveKitController.setMicrophoneEnabled
                // for the underlying SDK behaviour), the explicit toggles below
                // get a second crack at it now that the room is fully up.
                controller.setMicrophoneEnabled(initialMicEnabled)
                controller.setCameraEnabled(initialCameraEnabled)
            }.onSuccess {
                _state.update { it.copy(phase = RoomUiState.Phase.Connected) }
                ConferenceForegroundService.start(getApplication(), roomName)
                historyStore.upsertOnJoin(
                    roomId = roomId,
                    name = roomName,
                    slug = roomSlug,
                    host = host,
                    createdAtMs = createdAtMs,
                    selfName = selfName,
                )
                registerChatHandler()
                refreshParticipants()
                if (isAdmin) startLobbyPolling()
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        phase = RoomUiState.Phase.Error,
                        errorMessage = e.toUserMessage(getApplication()),
                    )
                }
            }
        }
    }

    @OptIn(io.livekit.android.annotations.Beta::class)
    private fun observeEvents() {
        viewModelScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected,
                    is RoomEvent.ParticipantDisconnected,
                    is RoomEvent.TrackSubscribed,
                    is RoomEvent.TrackUnsubscribed,
                    is RoomEvent.TrackPublished,
                    is RoomEvent.TrackUnpublished,
                    is RoomEvent.ParticipantAttributesChanged -> refreshParticipants()

                    // Server-initiated mute (host hit "mute" on our row,
                    // or we self-muted via setMicrophoneEnabled). The SDK
                    // already flipped the publication; sync the toolbar
                    // state so the local user sees what's actually true
                    // — otherwise the host mutes them, the icon still
                    // reads "mic on", they tap it expecting it to mute
                    // them, and the flow gets bewildering.
                    is RoomEvent.TrackMuted -> {
                        syncLocalTrackState(event.participant, event.publication, muted = true)
                        refreshParticipants()
                    }
                    is RoomEvent.TrackUnmuted -> {
                        syncLocalTrackState(event.participant, event.publication, muted = false)
                        refreshParticipants()
                    }

                    // Server flipped Room.isRecording. The SDK 2.23.5
                    // event class doesn't expose the boolean as a public
                    // property; read it off the Room instead — by the
                    // time the event fires, room.isRecording reflects
                    // the new value. Visible to ALL participants, not
                    // just the host who started recording.
                    is RoomEvent.RecordingStatusChanged -> {
                        _state.update {
                            it.copy(
                                isRecording = controller.room.isRecording,
                                recordingPending = false,
                            )
                        }
                    }

                    // Realtime transcription line(s) from meet-agent-
                    // subtitles. Web only keeps the first segment per id
                    // (Doubao Seed-ASR emits each line once it's stable,
                    // not repeated partials) — we do the same. Append in
                    // arrival order; UI groups consecutive same-speaker
                    // rows visually.
                    is RoomEvent.TranscriptionReceived -> {
                        appendSubtitleSegments(event)
                    }

                    is RoomEvent.ActiveSpeakersChanged -> {
                        activeSpeakerIds = event.speakers
                            .mapNotNull { it.identity?.value }
                            .toSet()
                        refreshParticipants()
                    }

                    is RoomEvent.DataReceived -> onLegacyChatPacket(event)

                    is RoomEvent.Disconnected -> {
                        // Ephemeral chat, matches web: "消息仅对发送时在场的
                        // 参与者可见。所有消息将在通话结束时删除。"
                        _state.update { it.copy(messages = emptyList()) }

                        // How the backend ends a meeting: the `/end/` endpoint
                        // calls LiveKit's `remove_participant` for every
                        // participant (see backend
                        // core/services/participants_management.py#remove_all).
                        // That surfaces on clients as PARTICIPANT_REMOVED, not
                        // ROOM_DELETED. We also keep ROOM_DELETED / ROOM_CLOSED
                        // as belt-and-suspenders for edge cases (LiveKit
                        // garbage-collecting empty rooms, direct DeleteRoom
                        // calls, etc.).
                        val hostEnded = !userInitiatedLeave && (
                            event.reason == DisconnectReason.PARTICIPANT_REMOVED ||
                                event.reason == DisconnectReason.ROOM_DELETED ||
                                event.reason == DisconnectReason.ROOM_CLOSED
                            )
                        if (hostEnded) {
                            // Tear down the "meeting in progress" notification
                            // immediately — the user is about to get popped
                            // back to Home with the host-ended sheet.
                            ConferenceForegroundService.stop(getApplication())
                        }
                        historyStore.markLeft(roomId)
                        _state.update {
                            it.copy(
                                phase = RoomUiState.Phase.Disconnected,
                                hostEnded = hostEnded,
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun refreshParticipants() {
        val local = controller.room.localParticipant
        val remotes = controller.room.remoteParticipants.values

        // Build identity → UI map from current room state. For each participant
        // we may emit two entries: the camera tile and, if they're publishing
        // a screen-share track, a separate screen-share tile.
        val byIdentity = linkedMapOf<String, ParticipantUi>()
        local.toUi(isLocal = true).let { byIdentity[it.identity] = it }
        local.screenShareUi(isLocal = true)?.let { byIdentity[it.identity] = it }
        remotes.forEach { p ->
            val ui = p.toUi(isLocal = false)
            byIdentity[ui.identity] = ui
            p.screenShareUi(isLocal = false)?.let { byIdentity[it.identity] = it }
        }

        // Maintain stable ordering: keep previously-seen identities first
        // in their original order, then append newcomers.
        orderedIdentities.retainAll(byIdentity.keys)
        byIdentity.keys.forEach { orderedIdentities.add(it) }

        val ordered = orderedIdentities.mapNotNull { byIdentity[it] }

        // Focus resolution:
        //   1. If the user has an explicit pin that still exists, keep it.
        //   2. Otherwise, auto-pin a remote screen-share so everyone sees the
        //      shared content big. We don't auto-pin our own screen-share —
        //      the sharer already knows what's being shared and is usually in
        //      another app anyway.
        //   3. Else clear focus (gallery).
        val focus = _state.value.focusIdentity
        val remoteShareId = ordered.firstOrNull { it.isScreenShare && !it.isLocal }?.identity
        val nextFocus = when {
            focus != null && byIdentity.containsKey(focus) -> focus
            remoteShareId != null -> remoteShareId
            else -> null
        }

        val localHasShare = byIdentity.values.any { it.isScreenShare && it.isLocal }

        // Note: do NOT overwrite mic/cameraEnabled from `local.isXxxEnabled`
        // here. Track publication is async, so right after connect or a
        // toggle the LiveKit-observed state lags the user's intent and
        // would flicker the toolbar button. The toggle functions own these
        // fields; this method only refreshes participant/focus data.
        _state.update {
            it.copy(
                participants = ordered,
                focusIdentity = nextFocus,
                localScreenSharing = localHasShare,
            )
        }

        // Accumulate real participant names for the history's 参会人 list.
        // Read directly from LiveKit (not the UI projection) so we never
        // record the "—" placeholder that toUi() falls back to during the
        // brief window after connect() returns but before the local
        // participant's identity has been populated.
        val participantNames = buildList {
            fun realName(p: Participant): String? =
                p.name?.takeIf { it.isNotBlank() }
                    ?: p.identity?.value?.takeIf { it.isNotBlank() }
            realName(local)?.let { add(it) }
            remotes.forEach { realName(it)?.let { name -> add(name) } }
        }
        if (participantNames.isNotEmpty()) {
            historyStore.recordParticipants(roomId, participantNames)
        }
    }

    /**
     * Reconcile [RoomUiState.micEnabled] / [RoomUiState.cameraEnabled]
     * with a SDK-emitted publication-mute change. Only fires for our own
     * publications — remote participants' mute state lives in the
     * per-tile [ParticipantUi.isMicEnabled] that [refreshParticipants]
     * already rebuilds from the SDK.
     *
     * The `refreshParticipants` body has a comment explaining why it
     * deliberately doesn't read mic/cam from the SDK (publish is async,
     * the LiveKit-observed state lags user intent and flickers the
     * toolbar). Server-initiated mute is the one case where we MUST
     * surface the SDK truth — the user can't know they were muted from
     * the toolbar alone, and any user-initiated toggle goes through
     * [toggleMic] / [toggleCamera] which already drives the state flag,
     * so flicker isn't a risk on the local-toggle path.
     */
    private fun syncLocalTrackState(
        participant: Participant,
        publication: io.livekit.android.room.track.TrackPublication,
        muted: Boolean,
    ) {
        if (participant != controller.room.localParticipant) return
        when (publication.source) {
            Track.Source.MICROPHONE -> _state.update { it.copy(micEnabled = !muted) }
            Track.Source.CAMERA -> _state.update { it.copy(cameraEnabled = !muted) }
            else -> Unit
        }
    }

    private fun Participant.toUi(isLocal: Boolean): ParticipantUi {
        val cameraPub = getTrackPublication(Track.Source.CAMERA)
        // When a participant turns off their camera, LiveKit typically mutes
        // the publication instead of unpublishing — `cameraPub.track` stays
        // non-null, and handing a muted track to VideoTrackView would keep
        // painting the last received frame (frozen image). Treat muted as
        // "no video" so the avatar placeholder kicks in.
        val videoTrack = if (cameraPub?.muted == false) cameraPub.track as? VideoTrack else null
        val id = identity?.value ?: sid.value
        return ParticipantUi(
            identity = id,
            name = name?.takeIf { it.isNotBlank() } ?: identity?.value ?: "—",
            isLocal = isLocal,
            isMicEnabled = isMicrophoneEnabled,
            videoTrack = videoTrack,
            isSpeaking = id in activeSpeakerIds,
            handRaisedAt = attributes[HAND_RAISED_ATTRIBUTE]?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Build the synthetic "screen-share" tile for a participant if they have
     * an unmuted SCREEN_SHARE publication. Returns null otherwise so the
     * gallery only shows the share tile while capture is live.
     *
     * For the LOCAL sharer we deliberately omit [ParticipantUi.videoTrack].
     * MediaProjection captures our own screen, so rendering the live track
     * on the sharer's own device would produce an infinite "mirror-in-mirror"
     * recursion. [ParticipantTile] detects this (isScreenShare && videoTrack
     * == null) and draws a static placeholder card instead — remote
     * participants still get the real track unchanged.
     */
    private fun Participant.screenShareUi(isLocal: Boolean): ParticipantUi? {
        val pub = getTrackPublication(Track.Source.SCREEN_SHARE) ?: return null
        if (pub.muted) return null
        val track = pub.track as? VideoTrack ?: return null
        val baseId = identity?.value ?: sid.value
        val suffix = ParticipantUi.SCREEN_SHARE_ID_SUFFIX
        val app = getApplication<Application>()
        val label = if (isLocal) {
            app.getString(com.we.meet.R.string.room_screen_share_self_sharing)
        } else {
            val displayName = name?.takeIf { it.isNotBlank() } ?: identity?.value ?: "—"
            app.getString(com.we.meet.R.string.room_screen_share_of, displayName)
        }
        return ParticipantUi(
            identity = baseId + suffix,
            name = label,
            isLocal = isLocal,
            isMicEnabled = true,
            videoTrack = if (isLocal) null else track,
            isSpeaking = false,
            isScreenShare = true,
        )
    }

    fun toggleMic() {
        val next = !_state.value.micEnabled
        // Optimistic UI — flip the toolbar immediately, then ask LiveKit.
        _state.update { it.copy(micEnabled = next) }
        viewModelScope.launch {
            runCatching { controller.setMicrophoneEnabled(next) }
                .onFailure { _state.update { it.copy(micEnabled = !next) } }
            refreshParticipants()
        }
    }

    fun toggleCamera() {
        val next = !_state.value.cameraEnabled
        _state.update { it.copy(cameraEnabled = next) }
        viewModelScope.launch {
            runCatching { controller.setCameraEnabled(next) }
                .onFailure { _state.update { it.copy(cameraEnabled = !next) } }
            refreshParticipants()
        }
    }

    fun switchCamera() {
        controller.switchCamera()
    }

    // ── Screen share ───────────────────────────────────────────────────────

    /**
     * Start publishing the local screen. [resultData] must be the Intent
     * returned from the MediaProjection consent activity launched by
     * [android.media.projection.MediaProjectionManager.createScreenCaptureIntent].
     *
     * Suspends until the SDK has set up the capturer and bound its
     * mediaProjection foreground service. The caller (RoomScreen) awaits
     * this before backgrounding the app to launch a target app — on
     * API 34+ the OS forbids starting a foreground service from the
     * background, so we must not let the launched app take over until the
     * SDK's own FGS is up.
     *
     * Returns true on success, false on failure (e.g. the consent Intent
     * was already consumed, or the SDK threw).
     */
    suspend fun startScreenShare(resultData: Intent): Boolean {
        if (_state.value.localScreenSharing) return true
        _state.update { it.copy(localScreenSharing = true) }
        return runCatching {
            controller.setScreenShareEnabled(
                enabled = true,
                mediaProjectionResultData = resultData,
                onSystemStop = {
                    // Fires when MediaProjection is revoked outside our
                    // control (user taps "Stop" on the system notification,
                    // screen lock, etc.). Reconcile UI.
                    viewModelScope.launch { stopScreenShare() }
                },
            )
        }.onFailure { e ->
            Log.w(TAG, "startScreenShare failed", e)
            _state.update { it.copy(localScreenSharing = false) }
        }.getOrDefault(false)
    }

    fun stopScreenShare() {
        if (!_state.value.localScreenSharing) return
        viewModelScope.launch {
            runCatching { controller.setScreenShareEnabled(enabled = false) }
                .onFailure { Log.w(TAG, "stopScreenShare failed", it) }
            _state.update { it.copy(localScreenSharing = false) }
            refreshParticipants()
        }
    }

    fun onLifecycleStop() {
        // Nothing to do. The in-meeting foreground service keeps cam/mic
        // and the LiveKit session alive while the app is in the background.
    }

    /**
     * With the foreground service running, the session stays healthy across
     * background/foreground cycles. We only need to cover one lingering
     * edge: if the network was actually lost past LiveKit's own retry limit
     * while we were gone (state=DISCONNECTED), try one fresh rejoin — the
     * Disconnected observer already decides whether that's "host ended" or
     * "real network drop", but if we ended up Disconnected for any
     * non-host-ended reason, trying a reconnect before giving up is worth
     * the one-shot cost.
     */
    fun onLifecycleStart() {
        if (_state.value.hostEnded) return
        if (controller.room.state != Room.State.DISCONNECTED) return
        // Only rejoin if we'd actually established a session first. During
        // the initial handshake, phase is Connecting — connect() runs from
        // init{}, don't stomp it with a parallel reconnect.
        if (_state.value.phase != RoomUiState.Phase.Disconnected) return
        Log.i(TAG, "onLifecycleStart: room state is DISCONNECTED, attempting reconnect")
        reconnectOrGiveUp()
    }

    private fun reconnectOrGiveUp() {
        viewModelScope.launch {
            _state.update { it.copy(phase = RoomUiState.Phase.Connecting) }
            val wantMic = _state.value.micEnabled
            val wantCamera = _state.value.cameraEnabled

            val result = runCatching {
                // `room.connect()` throws IllegalStateException unless state
                // is DISCONNECTED. If we're entering from a stuck
                // RECONNECTING, tear down cleanly first.
                if (controller.room.state != Room.State.DISCONNECTED) {
                    Log.i(TAG, "reconnect: forcing disconnect from state=${controller.room.state}")
                    controller.disconnect()
                    withTimeoutOrNull(3_000) {
                        while (controller.room.state != Room.State.DISCONNECTED) {
                            delay(50)
                        }
                    }
                }

                withTimeout(30_000) {
                    controller.connect(
                        livekitUrl, livekitToken,
                        audio = wantMic,
                        video = wantCamera,
                    )
                }
                // No strict `check` on state here — if connect() returned
                // without throwing, trust LiveKit. Some builds briefly
                // report CONNECTING right after connect() returns before
                // flipping to CONNECTED, and we don't want to treat that
                // benign race as "room is gone".
                controller.setMicrophoneEnabled(wantMic)
                controller.setCameraEnabled(wantCamera)
            }

            if (result.isSuccess) {
                Log.i(TAG, "reconnect: success")
                _state.update {
                    it.copy(
                        phase = RoomUiState.Phase.Connected,
                        sessionGeneration = it.sessionGeneration + 1,
                    )
                }
                ConferenceForegroundService.start(getApplication(), roomName)
                refreshParticipants()
                if (isAdmin) startLobbyPolling()
            } else {
                // Couldn't re-join — assume the room is gone (host ended
                // it, LiveKit GC'd an empty room, token expired). Flip to
                // the host-ended path so the user lands on Home with the
                // "主持人已结束会议" sheet, instead of being stuck on a
                // frozen dead room.
                Log.w(TAG, "reconnect: giving up", result.exceptionOrNull())
                ConferenceForegroundService.stop(getApplication())
                _state.update {
                    it.copy(
                        phase = RoomUiState.Phase.Disconnected,
                        hostEnded = true,
                    )
                }
            }
        }
    }

    /** Enter Focus mode pinning the given participant. */
    fun pinParticipant(identity: String) {
        _state.update { it.copy(focusIdentity = identity) }
    }

    /** Exit Focus mode. */
    fun unpinParticipant() {
        _state.update { it.copy(focusIdentity = null) }
    }

    fun leave() {
        userInitiatedLeave = true
        ConferenceForegroundService.stop(getApplication())
        controller.disconnect()
    }

    // ── In-meeting chat ────────────────────────────────────────────────
    //
    // We mirror the web client's chat protocol (see
    // @livekit/components-core's `setupChat`) which publishes on TWO topics
    // simultaneously and de-duplicates on receive by message id:
    //
    //   1. LiveKit Text Streams on topic `lk.chat` — requires server v1.8.2+.
    //   2. Legacy DataChannel on topic `lk-chat-topic` (JSON payload
    //      { id, timestamp, message, ignoreLegacy }) — what everyone falls
    //      back to when the server is older.
    //
    // Production we-meet currently runs livekit-server v1.7.2 (no Text-
    // Stream support), so (2) is the only path that actually reaches peers.
    // (1) is kept active for forward compatibility once the server upgrade
    // happens — the dedupe key is the message id, shared across both paths.

    /**
     * Register the LiveKit Text Stream handler for the reserved `lk.chat`
     * topic. LiveKit does not echo local sends to the handler, so local
     * messages are appended separately in [sendChatMessage]. Registration
     * uses `runCatching` inside the controller, so a no-op on reconnect
     * (handler already present) is safe.
     */
    private fun registerChatHandler() {
        controller.registerChatHandler { reader, identity ->
            viewModelScope.launch {
                val text = reader.flow.toList().joinToString(separator = "")
                val sender = controller.room.remoteParticipants.values
                    .firstOrNull { it.identity == identity }
                val displayName = sender?.name?.takeIf { it.isNotBlank() }
                    ?: sender?.identity?.value
                    ?: identity.value
                appendMessage(
                    ChatMessageUi(
                        id = reader.info.id,
                        senderIdentity = identity.value,
                        senderName = displayName,
                        isLocal = false,
                        isHost = false,
                        text = text,
                        timestampMs = reader.info.timestampMs,
                    ),
                )
            }
        }
    }

    /**
     * Decode an incoming legacy chat packet (topic `lk-chat-topic`) and
     * append it as a remote message. Honours the `ignoreLegacy` flag the
     * sender sets when they also published the same id via Text Streams —
     * we'll have already received the Text-Stream copy through the handler
     * above. Any other DataReceived topic is silently ignored.
     */
    private fun onLegacyChatPacket(event: RoomEvent.DataReceived) {
        if (event.topic != LiveKitController.LEGACY_CHAT_TOPIC) return
        runCatching {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            if (json.optBoolean("ignoreLegacy", false)) return
            val id = json.optString("id").ifEmpty { return }
            val text = json.optString("message")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val sender = event.participant
            val displayName = sender?.name?.takeIf { it.isNotBlank() }
                ?: sender?.identity?.value
                ?: "—"
            appendMessage(
                ChatMessageUi(
                    id = id,
                    senderIdentity = sender?.identity?.value ?: "",
                    senderName = displayName,
                    isLocal = false,
                    isHost = false,
                    text = text,
                    timestampMs = timestamp,
                ),
            )
        }.onFailure { Log.w(TAG, "onLegacyChatPacket: malformed payload", it) }
    }

    fun sendChatMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // 1. Try Text Streams (server v1.8.2+). Reuse its id/timestamp
            //    when it succeeds so the legacy copy collides on the same
            //    key for dedupe on the receiving end.
            val textStreamResult = runCatching { controller.sendChatText(trimmed) }
                .getOrElse { Result.failure(it) }
            val info = textStreamResult.getOrNull()
            val id = info?.id ?: UUID.randomUUID().toString()
            val timestamp = info?.timestampMs ?: System.currentTimeMillis()
            val textStreamOk = info != null

            // 2. Local echo — LiveKit never delivers local sends to our own
            //    handler, so we have to append it ourselves.
            val local = controller.room.localParticipant
            val name = local.name?.takeIf { it.isNotBlank() }
                ?: local.identity?.value
                ?: "—"
            appendMessage(
                ChatMessageUi(
                    id = id,
                    senderIdentity = local.identity?.value ?: "",
                    senderName = name,
                    isLocal = true,
                    isHost = isAdmin,
                    text = trimmed,
                    timestampMs = timestamp,
                ),
            )

            // 3. Always publish the legacy DataChannel copy. Peers on an
            //    old server (or peers like older Android builds that don't
            //    yet listen on text streams) will only ever see this one.
            //    ignoreLegacy=true tells modern peers to drop this copy if
            //    they already received the text-stream one.
            controller.sendChatLegacy(
                id = id,
                text = trimmed,
                timestampMs = timestamp,
                ignoreLegacy = textStreamOk,
            ).onFailure { Log.w(TAG, "sendChatLegacy failed", it) }

            if (!textStreamOk) {
                Log.i(TAG, "sendChatText unsupported on this server; legacy DataChannel only")
            }
        }
    }

    private fun appendMessage(message: ChatMessageUi) {
        _state.update { state ->
            // Dedupe across text-stream + legacy paths: same logical message
            // is sent on both topics with the same id, so the second arrival
            // is a no-op.
            if (state.messages.any { it.id == message.id }) state
            else state.copy(messages = state.messages + message)
        }
    }

    /**
     * Owner-only: kick the named participant from the room. The caller
     * (UI) is expected to have already gated this on [RoomUiState.isAdmin];
     * non-admins will get a 403 from the server anyway, but the gate
     * prevents wasted requests + spurious failure toasts.
     */
    suspend fun removeParticipant(identity: String): Result<Unit> {
        return roomRepository.removeParticipant(
            idOrSlug = roomId,
            identity = identity,
        )
    }

    /**
     * Owner-only: mute the named participant's microphone publication.
     * We look up the live LiveKit Participant + its mic track sid here
     * (instead of threading them through the call site) because the UI
     * only carries our flattened [ParticipantUi], not the raw SDK
     * publications. Returns failure if the target isn't present or
     * isn't currently publishing audio (the host either doesn't see
     * a mic icon at all, or sees it already off — silently no-op).
     */
    suspend fun muteParticipantMicrophone(identity: String): Result<Unit> {
        val target = controller.room.remoteParticipants.values
            .firstOrNull { it.identity?.value == identity }
            ?: return Result.failure(IllegalStateException("Participant not in room"))
        val micSid = target.getTrackPublication(Track.Source.MICROPHONE)?.sid
            ?: return Result.success(Unit)
        return roomRepository.muteParticipantMicrophone(
            idOrSlug = roomId,
            livekitToken = livekitToken,
            identity = identity,
            micTrackSid = micSid,
        )
    }

    /**
     * Toggle the local participant's raised-hand state. The "current
     * state" comes from our own ParticipantUi snapshot (sourced from the
     * `handRaisedAt` attribute), so a stale state flow can't cause us to
     * request `raised=true` when the hand is already up — the next
     * ParticipantAttributesChanged event will keep the UI in sync either
     * way. We don't optimistically flip the local state here; the server
     * echoes the attribute change back to all clients (including us) and
     * [refreshParticipants] picks it up via the event subscription.
     */
    suspend fun toggleHand(): Result<Unit> {
        val currentlyRaised = _state.value.participants
            .firstOrNull { it.isLocal && !it.isScreenShare }
            ?.handRaisedAt != null
        return roomRepository.toggleHand(
            idOrSlug = roomId,
            livekitToken = livekitToken,
            raised = !currentlyRaised,
        )
    }

    /**
     * Rename the local participant via the backend `/rename/` endpoint.
     * The change propagates to other participants via LiveKit's
     * `ParticipantAttributesChanged` / `ParticipantNameChanged` events,
     * which we already invalidate the UI on via [refreshParticipants].
     *
     * Trimmed/empty input is a no-op so callers don't need to validate
     * twice. Returns Result so the dialog can show a toast on failure.
     */
    suspend fun renameSelf(name: String): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.success(Unit)
        return roomRepository.renameSelf(
            idOrSlug = roomId,
            livekitToken = livekitToken,
            name = trimmed,
        ).onSuccess {
            // LiveKit pushes the new display name back to all clients as a
            // ParticipantAttributesChanged event, but for the local user
            // the SDK's own `localParticipant.name` only updates when the
            // server echoes — so we proactively re-sync the participant
            // list to pick up the new name without waiting for the event.
            refreshParticipants()
        }
    }

    /**
     * Start the owner-side lobby polling loop. Idempotent — if a poll
     * job is already in flight, re-use it. 3 s interval is a balance
     * between freshness (visitor's request-entry timeout is 30 s, so
     * we want owner to see new entries before they expire) and battery.
     * The poll silently swallows failures so a transient network blip
     * doesn't tear down the loop; the next tick recovers.
     */
    private fun startLobbyPolling() {
        if (lobbyPollJob?.isActive == true) return
        lobbyPollJob = viewModelScope.launch {
            while (true) {
                roomRepository.listWaitingParticipants(roomId)
                    .onSuccess { list ->
                        _state.update { it.copy(waitingParticipants = list) }
                    }
                // Don't log every transient failure — they're expected
                // during reconnect windows. Swallow and retry.
                delay(3_000)
            }
        }
    }

    /**
     * Owner admit (allow=true) / deny (allow=false) a single waiting
     * participant. Optimistically removes them from the local snapshot
     * so the sheet reacts instantly; the next poll tick (or a poll
     * triggered by another admin's action) will reconcile if needed.
     */
    suspend fun admitParticipant(participantId: String, allow: Boolean): Result<Unit> {
        return roomRepository.admitParticipant(roomId, participantId, allow)
            .onSuccess {
                _state.update { s ->
                    s.copy(waitingParticipants = s.waitingParticipants.filterNot { it.id == participantId })
                }
            }
    }

    /**
     * Pull the current access_level from the backend. Called lazily
     * when the host opens the settings sheet — we don't preload at
     * connect time because non-owners never see this UI and the value
     * is cheap to fetch on demand. Updates the state on success;
     * failure leaves the existing state untouched so a network blip
     * doesn't blank out the sheet's radio selection.
     */
    suspend fun refreshAccessLevel() {
        roomRepository.getRoom(roomId, username = selfName).onSuccess { room ->
            _state.update { it.copy(accessLevel = room.access_level) }
        }
    }

    /**
     * Host-only: change the room's access level. On success the local
     * state is updated immediately so the sheet's radio reflects the
     * new value without waiting for another GET. Failure surfaces via
     * the returned Result so the UI can show a toast.
     */
    suspend fun updateAccessLevel(accessLevel: String): Result<Unit> {
        return roomRepository.updateAccessLevel(roomId, accessLevel)
            .map { room ->
                _state.update { it.copy(accessLevel = room.access_level) }
                Unit
            }
    }

    /**
     * Host-only: toggle audio-transcription recording. Backend uses the
     * room's UUID OR its slug interchangeably; slug is what we have at
     * VM-construction time so we hit `start-recording`/`stop-recording`
     * with that. The LiveKit RoomEvent.RecordingStatusChanged broadcast
     * is what flips [RoomUiState.isRecording] for everyone — this
     * function only fires the HTTP call and toggles a local pending
     * flag while we wait. Caller is expected to gate on isAdmin.
     */
    fun toggleRecording() {
        if (!isAdmin) return
        val current = _state.value
        if (current.recordingPending) return
        _state.update { it.copy(recordingPending = true) }
        viewModelScope.launch {
            val result =
                if (current.isRecording) roomRepository.stopRecording(roomId)
                else roomRepository.startRecording(roomId)
            // On failure (409 already running, 502 worker error, 403 etc.)
            // drop the pending flag so the button re-enables; the server
            // state stays whatever it was. The RecordingStatusChanged
            // event will land separately on success.
            if (result.isFailure) {
                _state.update { it.copy(recordingPending = false) }
            }
        }
    }

    /**
     * Toggle the subtitle overlay. First flip-on also POSTs
     * `start-subtitle` to the backend — subsequent flips are pure
     * client-side overlay toggles (the backend agent stays attached
     * to the room until disconnect; there is no stop-subtitle).
     *
     * Anonymous and registered participants alike can start subtitles
     * — the backend authorises off the LiveKit token rather than the
     * Keycloak session, so anyone in the room is allowed.
     */
    fun toggleSubtitles() {
        val current = _state.value
        if (current.subtitlesPending) return
        // Just hiding the overlay — no network round-trip.
        if (current.subtitlesOverlayOn) {
            _state.update { it.copy(subtitlesOverlayOn = false) }
            return
        }
        // Showing the overlay: lazy-start the backend agent if we
        // haven't already this session.
        if (subtitlesStartedOnBackend) {
            _state.update { it.copy(subtitlesOverlayOn = true) }
            return
        }
        _state.update { it.copy(subtitlesPending = true) }
        viewModelScope.launch {
            val result = roomRepository.startSubtitle(roomId, livekitToken)
            if (result.isSuccess) {
                subtitlesStartedOnBackend = true
                _state.update { it.copy(subtitlesPending = false, subtitlesOverlayOn = true) }
            } else {
                _state.update { it.copy(subtitlesPending = false) }
            }
        }
    }

    /**
     * Snapshot a TranscriptionReceived event into our flat
     * SubtitleSegment list. Dedup by SDK segment id (Doubao Seed-ASR
     * emits one event per stable line, but defensive against double
     * delivery from reconnects). Cap the list at a generous bound so
     * a multi-hour meeting doesn't grow it without limit.
     */
    @OptIn(io.livekit.android.annotations.Beta::class)
    private fun appendSubtitleSegments(event: RoomEvent.TranscriptionReceived) {
        val participant = event.participant ?: return
        val identity = participant.identity?.value ?: return
        val name = participant.name?.takeIf { it.isNotBlank() }
            ?: participant.identity?.value
            ?: ""
        val incoming = event.transcriptionSegments
        if (incoming.isEmpty()) return
        _subtitleSegments.update { existing ->
            val seen = existing.mapTo(HashSet()) { it.id }
            val fresh = incoming
                .filter { it.id !in seen }
                .map { seg ->
                    SubtitleSegment(
                        id = seg.id,
                        participantIdentity = identity,
                        participantName = name,
                        text = seg.text,
                        timestamp = seg.firstReceivedTime,
                    )
                }
            if (fresh.isEmpty()) existing
            else (existing + fresh).takeLast(SUBTITLE_BUFFER_MAX)
        }
    }

    /** End the room via backend API, then disconnect. Only owner should call this. */
    fun endMeeting(onDone: () -> Unit) {
        userInitiatedLeave = true
        viewModelScope.launch {
            roomRepository.endRoom(roomId)
            ConferenceForegroundService.stop(getApplication())
            controller.disconnect()
            onDone()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Safety net: if the user closes the app / the VM is destroyed before
        // the Disconnected event lands, still record a leave timestamp so the
        // history entry isn't stuck with a null 离开时间.
        historyStore.markLeft(roomId)
        ConferenceForegroundService.stop(getApplication())
        controller.disconnect()
        controller.release()
    }

    class Factory(
        private val application: Application,
        private val roomId: String,
        private val livekitUrl: String,
        private val livekitToken: String,
        private val roomName: String,
        private val roomSlug: String,
        private val host: String?,
        private val createdAtMs: Long,
        private val initialMicEnabled: Boolean = true,
        private val initialCameraEnabled: Boolean = true,
        private val isAdmin: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as WeMeetApp
            val selfName = app.tokenStore.nickname?.takeIf { it.isNotBlank() }
                ?: app.tokenStore.phone
                ?: "Android User"
            return RoomViewModel(
                application, roomId, livekitUrl, livekitToken, roomName, roomSlug,
                app.roomRepository, app.historyStore, selfName, host, createdAtMs,
                initialMicEnabled, initialCameraEnabled, isAdmin,
            ) as T
        }
    }
}
