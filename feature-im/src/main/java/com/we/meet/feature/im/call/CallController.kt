package com.we.meet.feature.im.call

import android.util.Log
import com.jusi.lightim.CallEvent
import com.jusi.lightim.CallPayload
import com.jusi.lightim.Client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * P1 一对一通话 — client-side call state machine (design doc:
 * we-meet/docs/extensions/一对一通话P1_呼叫信令详细设计.md §2.3).
 *
 * The server is a stateless validating relay, so EVERYTHING stateful lives
 * here: call_id idempotency, invite re-send (3s × 3 until `ringing`),
 * independent 60s timeouts on both sides, busy auto-reply, multi-device
 * convergence (a terminal frame for the active callId ends the local attempt
 * regardless of which device produced it).
 *
 * Room lifecycle: the caller creates the LiveKit room BEFORE inviting (slug
 * rides in the invite) and is responsible for ending it on every
 * non-connected terminal. The callee resolves the slug to a token on accept.
 * Both go through [CallHost] — this module doesn't know Retrofit.
 */
class CallController(
    private val client: Client,
    private val scope: CoroutineScope,
    private val selfUid: () -> String?,
    private val host: CallHost?,
) {

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    /** One-shot navigation events (enter room). State alone can't model "navigate once". */
    private val _events = MutableSharedFlow<CallUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CallUiEvent> = _events.asSharedFlow()

    /**
     * Non-connected terminal outcomes ask the CHAT layer to drop a call-log
     * message into the conversation (拍板 #2). Emitted only on the caller side
     * so exactly one party persists it.
     */
    private val _callLogRequests = MutableSharedFlow<CallLogRequest>(extraBufferCapacity = 4)
    val callLogRequests: SharedFlow<CallLogRequest> = _callLogRequests.asSharedFlow()

    private var timeoutJob: Job? = null
    private var resendJob: Job? = null

    /**
     * Set on the CALLER's accept; consumed by [onCallRoomEnded] to write the
     * "completed + duration" call-log when the LiveKit session tears down.
     */
    private var connectedCall: ConnectedCall? = null

    /** callIds already terminally settled — late/duplicate frames are dropped. */
    private val finishedCallIds = ArrayDeque<String>()

    init {
        scope.launch { client.calls.collect { onFrame(it) } }
    }

    // ---- caller side ----

    /**
     * 发起通话: create the room, send the invite, enter Outgoing. Any failure
     * before the invite leaves the machine Idle with an [CallUiEvent.Error].
     */
    fun startCall(cid: String, peerUid: String, peerName: String, roomName: String, video: Boolean) {
        val h = host ?: run { emitError("call host missing"); return }
        if (_state.value != CallState.Idle || h.isInMeeting()) {
            emitError("busy-local")
            return
        }
        val info = CallInfo(
            callId = UUID.randomUUID().toString(),
            cid = cid,
            peerUid = peerUid,
            peerName = peerName,
            media = if (video) "video" else "audio",
            outgoing = true,
        )
        _state.value = CallState.Outgoing(info, waitingAnswer = false)
        scope.launch {
            val room = try {
                h.createCallRoom(roomName)
            } catch (e: Throwable) {
                Log.w(TAG, "createCallRoom failed", e)
                finish(info, CallEndReason.FAILED)
                return@launch
            }
            val withRoom = info.copy(room = room)
            // Re-check: user may have canceled while the room was being created.
            if ((_state.value as? CallState.Outgoing)?.info?.callId != info.callId) {
                runCatching { h.endCallRoom(room.roomId) }
                return@launch
            }
            _state.value = CallState.Outgoing(withRoom, waitingAnswer = false)
            sendFrame(withRoom, CallEvent.INVITE) { failed ->
                if (failed) finish(withRoom, CallEndReason.FAILED)
            }
            startInviteResend(withRoom)
            startTimeout(withRoom, asCaller = true)
        }
    }

    /** 主叫取消 (user tap / back). */
    fun cancel() {
        val out = _state.value as? CallState.Outgoing ?: return
        sendFrame(out.info, CallEvent.CANCEL, reason = "canceled")
        requestCallLog(out.info, "canceled")
        finish(out.info, CallEndReason.CANCELED, endRoom = true)
    }

    // ---- callee side ----

    /**
     * P2: seed an incoming call from an offline push. The WS invite frame is
     * gone (no offline buffering) — the push payload IS the invite. Freshness
     * is checked against the caller's 60s window; the local ring lasts only
     * the REMAINDER so both sides still converge without talking.
     */
    fun seedIncoming(seed: CallSeed) {
        if (seed.callId.isBlank() || seed.fromUid.isBlank() || seed.cid.isBlank()) return
        if (seed.callId in finishedCallIds) return
        val ageMs = System.currentTimeMillis() - seed.tsMs
        if (ageMs !in 0 until SEED_MAX_AGE_MS) {
            Log.i(TAG, "seedIncoming: stale push (age=${ageMs}ms), dropping")
            return
        }
        val current = _state.value
        if ((current as? WithInfo)?.info?.callId == seed.callId) return // dup push tap
        if (current != CallState.Idle || host?.isInMeeting() == true) {
            // Busy: best-effort busy reply (retried — WS may still be dialing).
            sendRetrying(seed.callId, seed.cid, seed.fromUid, CallEvent.BUSY, "busy") { false }
            return
        }
        val info = CallInfo(
            callId = seed.callId,
            cid = seed.cid,
            peerUid = seed.fromUid,
            // Push carries the resolved display name (Django side); the
            // incoming screen's directory lookup still refines it if needed.
            peerName = seed.fromName ?: seed.fromUid,
            media = seed.media,
            outgoing = false,
            roomSlug = seed.roomSlug,
        )
        _state.value = CallState.Incoming(info)
        // Ringing receipt flips the caller to 等待接听. The process may have
        // just been woken — the socket is still connecting, so retry.
        sendRetrying(info.callId, info.cid, info.peerUid, CallEvent.RINGING) {
            (_state.value as? CallState.Incoming)?.info?.callId != info.callId
        }
        startTimeout(info, asCaller = false, timeoutMs = RING_TIMEOUT_MS - ageMs)
    }

    /** 被叫接听: send accept, resolve the slug to a LiveKit token, enter the room. */
    fun accept() {
        val inc = _state.value as? CallState.Incoming ?: return
        val h = host ?: run { finish(inc.info, CallEndReason.FAILED); return }
        val slug = inc.info.roomSlug
        if (slug.isNullOrBlank()) {
            finish(inc.info, CallEndReason.FAILED)
            return
        }
        _state.value = CallState.Connecting(inc.info)
        // Retried: on a push-seeded call the WS may still be reconnecting when
        // the user taps 接听 — a lost accept would leave the caller ringing
        // into a room the callee already joined.
        sendRetrying(inc.info.callId, inc.info.cid, inc.info.peerUid, CallEvent.ACCEPT) {
            inc.info.callId in finishedCallIds
        }
        scope.launch {
            val room = try {
                h.resolveCallRoom(slug)
            } catch (e: Throwable) {
                Log.w(TAG, "resolveCallRoom failed", e)
                null
            }
            if (room == null) {
                finish(inc.info, CallEndReason.FAILED)
                return@launch
            }
            _events.tryEmit(CallUiEvent.EnterRoom(room, inc.info))
            finishQuietly(inc.info)
        }
    }

    /** 被叫拒接. */
    fun reject() {
        val inc = _state.value as? CallState.Incoming ?: return
        sendFrame(inc.info, CallEvent.REJECT, reason = "declined")
        finish(inc.info, CallEndReason.DECLINED_LOCAL)
    }

    // ---- inbound frames ----

    private fun onFrame(p: CallPayload) {
        val self = selfUid()
        when (p.event) {
            CallEvent.INVITE -> onInvite(p, self)
            CallEvent.RINGING -> onRinging(p)
            CallEvent.ACCEPT -> onAccept(p, self)
            CallEvent.REJECT -> onTerminal(p, self, CallEndReason.DECLINED, "declined")
            CallEvent.CANCEL -> onTerminal(p, self, CallEndReason.CANCELED_REMOTE, null)
            CallEvent.BUSY -> onTerminal(p, self, CallEndReason.BUSY, "busy")
            CallEvent.HANGUP -> onTerminal(p, self, CallEndReason.HUNG_UP, null)
            CallEvent.UNREACHABLE -> onTerminal(p, self, CallEndReason.UNREACHABLE, "unreachable")
        }
    }

    private fun onInvite(p: CallPayload, self: String?) {
        val from = p.from
        if (from == null || from == self) return
        if (p.callId in finishedCallIds) return
        val current = _state.value
        val busy = current != CallState.Idle || (host?.isInMeeting() == true)
        if (busy) {
            val active = (current as? WithInfo)?.info?.callId
            // Duplicate invite for the call we're already ringing on → it's the
            // caller's re-send; answer ringing again instead of busy.
            if (active == p.callId) {
                replyTo(p, CallEvent.RINGING)
            } else {
                replyTo(p, CallEvent.BUSY, reason = "busy")
            }
            return
        }
        val info = CallInfo(
            callId = p.callId,
            cid = p.cid,
            peerUid = from,
            // Display name is resolved by the incoming-call screen via the
            // user directory; the uid is only the fallback.
            peerName = from,
            media = p.media ?: "audio",
            outgoing = false,
            roomSlug = p.roomSlug,
            roomName = p.roomName,
        )
        _state.value = CallState.Incoming(info)
        replyTo(p, CallEvent.RINGING)
        startTimeout(info, asCaller = false)
    }

    private fun onRinging(p: CallPayload) {
        val out = _state.value as? CallState.Outgoing ?: return
        if (out.info.callId != p.callId) return
        resendJob?.cancel()
        if (!out.waitingAnswer) _state.value = out.copy(waitingAnswer = true)
    }

    private fun onAccept(p: CallPayload, self: String?) {
        val current = _state.value
        val info = (current as? WithInfo)?.info ?: return
        if (info.callId != p.callId) return
        when (current) {
            is CallState.Outgoing -> {
                // Peer accepted: enter the room we created.
                val room = info.room
                if (room == null) {
                    finish(info, CallEndReason.FAILED, endRoom = true)
                    return
                }
                // Track the connected call so the meeting-FGS teardown can
                // persist a "通话时长 mm:ss" log (caller side only — exactly
                // one party writes chat records, same as 拍板 #2).
                connectedCall = ConnectedCall(
                    cid = info.cid,
                    media = info.media,
                    startedAtMs = System.currentTimeMillis(),
                )
                _events.tryEmit(CallUiEvent.EnterRoom(room, info))
                finishQuietly(info)
            }
            is CallState.Incoming -> if (p.from == self) {
                // Another of MY devices answered (terminal double-publish echo).
                finish(info, CallEndReason.ANSWERED_ELSEWHERE)
            }
            else -> Unit
        }
    }

    private fun onTerminal(p: CallPayload, self: String?, reason: CallEndReason, logResult: String?) {
        val current = _state.value as? WithInfo ?: return
        val info = current.info
        if (info.callId != p.callId) return
        // My own echo of a terminal I initiated — already handled locally. On
        // a still-ringing OTHER device of mine, converge with the right label:
        // an echoed reject means "declined elsewhere", not "answered".
        if (p.from == self && reason != CallEndReason.HUNG_UP) {
            if (_state.value is CallState.Incoming) {
                finish(
                    info,
                    if (reason == CallEndReason.DECLINED) CallEndReason.DECLINED_ELSEWHERE
                    else CallEndReason.ANSWERED_ELSEWHERE,
                )
            }
            return
        }
        // Caller-side non-connected terminals persist a call-log (拍板 #2).
        if (info.outgoing && logResult != null) requestCallLog(info, logResult)
        finish(info, reason, endRoom = info.outgoing)
    }

    /**
     * Called by the host when the LiveKit session ends (meeting FGS teardown).
     * If a connected 1:1 call was in flight on this device as the CALLER,
     * persist the "completed + duration" call-log. Meetings and callee-side
     * sessions no-op ([connectedCall] is only set on the caller's accept).
     */
    fun onCallRoomEnded() {
        val c = connectedCall ?: return
        connectedCall = null
        val durationSec = ((System.currentTimeMillis() - c.startedAtMs) / 1000).coerceAtLeast(1)
        _callLogRequests.tryEmit(
            CallLogRequest(cid = c.cid, media = c.media, result = "completed", durationSec = durationSec)
        )
    }

    // ---- timers ----

    private fun startInviteResend(info: CallInfo) {
        resendJob?.cancel()
        resendJob = scope.launch {
            repeat(INVITE_RESENDS) {
                delay(INVITE_RESEND_INTERVAL_MS)
                val out = _state.value as? CallState.Outgoing ?: return@launch
                if (out.info.callId != info.callId || out.waitingAnswer) return@launch
                sendFrame(info, CallEvent.INVITE)
            }
        }
    }

    private fun startTimeout(info: CallInfo, asCaller: Boolean, timeoutMs: Long = RING_TIMEOUT_MS) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs.coerceAtLeast(1_000L))
            val current = _state.value as? WithInfo ?: return@launch
            if (current.info.callId != info.callId) return@launch
            if (asCaller) {
                sendFrame(info, CallEvent.CANCEL, reason = "timeout")
                requestCallLog(info, "missed")
            }
            finish(info, CallEndReason.TIMEOUT, endRoom = asCaller)
        }
    }

    // ---- plumbing ----

    private fun sendFrame(
        info: CallInfo,
        event: String,
        reason: String? = null,
        onResult: ((failed: Boolean) -> Unit)? = null,
    ) {
        try {
            client.sendCall(
                CallPayload(
                    event = event,
                    callId = info.callId,
                    cid = info.cid,
                    to = info.peerUid,
                    media = info.media,
                    roomSlug = info.room?.slug ?: info.roomSlug,
                    roomName = info.room?.roomName ?: info.roomName,
                    reason = reason,
                )
            )
            onResult?.invoke(false)
        } catch (e: Throwable) {
            Log.w(TAG, "sendCall($event) failed", e)
            onResult?.invoke(true)
        }
    }

    /**
     * Send a frame with a short retry loop — for the push-seeded path where
     * the process was just woken and the WS is still dialing. Stops early when
     * [obsolete] turns true (the call settled some other way) or one send
     * succeeds. Fire-and-forget by design: after the retries the usual
     * timeout/converge machinery is the fallback.
     */
    private fun sendRetrying(
        callId: String,
        cid: String,
        to: String,
        event: String,
        reason: String? = null,
        obsolete: () -> Boolean,
    ) {
        scope.launch {
            repeat(SEND_RETRIES) { attempt ->
                if (obsolete()) return@launch
                val ok = try {
                    client.sendCall(
                        CallPayload(event = event, callId = callId, cid = cid, to = to, reason = reason)
                    )
                    true
                } catch (e: Throwable) {
                    Log.i(TAG, "sendCall($event) attempt ${attempt + 1} failed: ${e.message}")
                    false
                }
                if (ok) return@launch
                delay(SEND_RETRY_INTERVAL_MS)
            }
            Log.w(TAG, "sendCall($event) gave up after $SEND_RETRIES attempts")
        }
    }

    private fun replyTo(p: CallPayload, event: String, reason: String? = null) {
        try {
            client.sendCall(
                CallPayload(
                    event = event, callId = p.callId, cid = p.cid,
                    to = p.from ?: return, reason = reason,
                )
            )
        } catch (e: Throwable) {
            Log.w(TAG, "sendCall(reply $event) failed", e)
        }
    }

    private fun requestCallLog(info: CallInfo, result: String) {
        _callLogRequests.tryEmit(CallLogRequest(cid = info.cid, media = info.media, result = result))
    }

    private fun finish(info: CallInfo, reason: CallEndReason, endRoom: Boolean = false) {
        rememberFinished(info.callId)
        timeoutJob?.cancel()
        resendJob?.cancel()
        if (endRoom) {
            val roomId = info.room?.roomId
            val h = host
            if (roomId != null && h != null) {
                scope.launch { runCatching { h.endCallRoom(roomId) } }
            }
        }
        _state.value = CallState.Ended(info, reason)
        // Ended is transient — screens toast the reason and pop; auto-reset so
        // the next call doesn't have to race a stale terminal state.
        scope.launch {
            delay(ENDED_LINGER_MS)
            if ((_state.value as? CallState.Ended)?.info?.callId == info.callId) {
                _state.value = CallState.Idle
            }
        }
    }

    /** Terminal without an Ended broadcast — used when handing off to the room. */
    private fun finishQuietly(info: CallInfo) {
        rememberFinished(info.callId)
        timeoutJob?.cancel()
        resendJob?.cancel()
        _state.value = CallState.Idle
    }

    private fun rememberFinished(callId: String) {
        finishedCallIds.addLast(callId)
        while (finishedCallIds.size > FINISHED_CACHE) finishedCallIds.removeFirst()
    }

    private fun emitError(message: String) {
        _events.tryEmit(CallUiEvent.Error(message))
    }

    companion object {
        private const val TAG = "CallController"
        private const val RING_TIMEOUT_MS = 60_000L        // 拍板 #1
        private const val INVITE_RESEND_INTERVAL_MS = 3_000L
        private const val INVITE_RESENDS = 3
        private const val ENDED_LINGER_MS = 2_500L
        private const val FINISHED_CACHE = 32

        // P2 push-seeded calls: matches the push TTL (55s) — older seeds mean
        // the caller's 60s window is (nearly) over, ringing would be cruel.
        private const val SEED_MAX_AGE_MS = 55_000L
        private const val SEND_RETRIES = 5
        private const val SEND_RETRY_INTERVAL_MS = 1_000L
    }
}

/**
 * P2: one call invite carried by an offline push (`type:"call"`), parsed from
 * the transparent-message / deeplink JSON. Field names mirror the wire payload
 * (see docs 一对一通话P2 §3); both the Getui receiver and MainActivity's
 * `wemeet://call` deeplink funnel through [fromJson].
 */
data class CallSeed(
    val callId: String,
    val cid: String,
    val fromUid: String,
    val fromName: String?,
    val media: String,
    val roomSlug: String?,
    val tsMs: Long,
) {
    companion object {
        /** Lenient parse — null on malformed/incomplete JSON (drop the push). */
        fun fromJson(json: String): CallSeed? = try {
            val o = org.json.JSONObject(json)
            val callId = o.optString("call_id")
            val cid = o.optString("cid")
            val from = o.optString("from")
            if (callId.isBlank() || cid.isBlank() || from.isBlank()) null
            else CallSeed(
                callId = callId,
                cid = cid,
                fromUid = from,
                fromName = o.optString("from_name").takeIf { it.isNotBlank() },
                media = o.optString("media").ifBlank { "audio" },
                roomSlug = o.optString("room_slug").takeIf { it.isNotBlank() },
                tsMs = o.optLong("ts"),
            )
        } catch (_: Throwable) {
            null
        }
    }
}

// ---- contracts & models ----

/**
 * Host-injected room operations (app module owns RoomRepository). Mirrors the
 * ImDeps pattern: feature-im stays Retrofit-free.
 */
interface CallHost {
    /** POST /rooms/ — returns the created room with its LiveKit credentials. */
    suspend fun createCallRoom(name: String): CallRoom

    /** GET /rooms/{slug}/ — null when the room is gone/ended (call already over). */
    suspend fun resolveCallRoom(slug: String): CallRoom?

    /** Best-effort owner end — prevents zombie rooms (design §5.4). */
    suspend fun endCallRoom(roomId: String)

    /** True while the user is in any LiveKit room (meeting or call) → busy. */
    fun isInMeeting(): Boolean
}

/** LiveKit room target for one call — both sides end up with one of these. */
data class CallRoom(
    val roomId: String,
    val slug: String,
    val roomName: String,
    val livekitUrl: String,
    val livekitToken: String,
    val createdAtMs: Long,
)

data class CallInfo(
    val callId: String,
    val cid: String,
    val peerUid: String,
    val peerName: String,
    /** "audio" | "video" */
    val media: String,
    val outgoing: Boolean,
    val room: CallRoom? = null,
    /** Callee side: slug/name carried by the invite (room not resolved yet). */
    val roomSlug: String? = null,
    val roomName: String? = null,
)

/** Marker for states that carry a [CallInfo]. */
private interface WithInfo { val info: CallInfo }

sealed interface CallState {
    data object Idle : CallState
    /** Caller: invite out. [waitingAnswer] flips true once the callee's device rang. */
    data class Outgoing(override val info: CallInfo, val waitingAnswer: Boolean) : CallState, WithInfo
    /** Callee: ringing. */
    data class Incoming(override val info: CallInfo) : CallState, WithInfo
    /** Callee: accepted, resolving the room. */
    data class Connecting(override val info: CallInfo) : CallState, WithInfo
    /** Transient terminal — screens show the reason and pop; auto-resets to Idle. */
    data class Ended(override val info: CallInfo, val reason: CallEndReason) : CallState, WithInfo
}

enum class CallEndReason {
    CANCELED,           // I canceled
    CANCELED_REMOTE,    // caller canceled / timed out while I rang
    DECLINED,           // peer rejected my call
    DECLINED_LOCAL,     // I rejected
    TIMEOUT,            // 60s, no answer
    BUSY,               // peer busy
    UNREACHABLE,        // peer offline (server short-circuit)
    HUNG_UP,            // in-call hangup (P1: via room, frame reserved)
    ANSWERED_ELSEWHERE, // another of my devices took it
    DECLINED_ELSEWHERE, // another of my devices rejected it
    FAILED,             // room create/resolve or socket failure
}

sealed interface CallUiEvent {
    /** Navigate into the LiveKit room. audio call → cam off. */
    data class EnterRoom(val room: CallRoom, val info: CallInfo) : CallUiEvent
    data class Error(val message: String) : CallUiEvent
}

/**
 * Ask the chat layer to persist a call-log message (caller side only).
 * [durationSec] is non-zero only for result="completed" (通话时长).
 */
data class CallLogRequest(
    val cid: String,
    val media: String,
    val result: String,
    val durationSec: Long = 0,
)

/** A connected 1:1 call in flight on this device as the caller (drives the duration log). */
internal data class ConnectedCall(
    val cid: String,
    val media: String,
    val startedAtMs: Long,
)
