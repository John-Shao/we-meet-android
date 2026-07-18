package com.we.meet.feature.im.call

import android.util.Log
import com.jusi.lightim.CallEvent
import com.jusi.lightim.CallPayload
import com.jusi.lightim.Client
import com.we.meet.feature.im.data.ImBridgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * P4 通话中拉人升级会议 — per-invitee escalation-invite tracker (mirrors the
 * web meetInviteTracker.ts so both ends speak the same protocol).
 *
 * Independent of [CallController] on purpose: that machine is single-channel
 * (`Idle`-gated, one ringing call at a time) while an escalation fans out N
 * parallel 1:1 invites from INSIDE a live room. Each invite is a standard P1
 * frame sent over the inviter↔invitee direct conversation with `kind:"meet"`
 * and the CURRENT room's slug (no new room), so busy/unreachable/timeout and
 * the P2 offline push ride along for free.
 *
 * Responsibilities (and nothing more): send + 3s×3 re-send + 60s timeout +
 * per-invitee state for the voice grid. No room create, no navigation.
 * P4-M3 订正:未接通的 meet 邀请落一条带 kind:"meet" 的 call-log(渲染为
 * 「会议邀请 · 未接/已拒绝…」)——当年不写记录是防伪装成未接电话,样式化后
 * 解禁;group(群语音)邀请仍不落 direct 记录(群只留群记录)。
 */
class MeetInviteTracker internal constructor(
    private val client: Client,
    private val scope: CoroutineScope,
    private val bridge: ImBridgeRepository,
) {

    enum class InviteState { INVITING, RINGING, ACCEPTED, REJECTED, BUSY, UNREACHABLE, TIMEOUT, CANCELED, FAILED }

    data class MeetInvite(
        val callId: String,
        /** we-meet user uuid (picker output). */
        val userId: String,
        /** Display label captured at pick time (grid chip + fallbacks). */
        val label: String,
        /** Presigned avatar URL captured at pick time (dimmed grid chip). */
        val avatarUrl: String? = null,
        val state: InviteState,
        val peerUid: String? = null,
        val cid: String? = null,
        /** P4-M3 未接记录用:发邀时的媒介与 kind(group 不落 direct 记录)。 */
        val media: String = "audio",
        val kind: String = "meet",
    ) {
        val terminal: Boolean
            get() = state != InviteState.INVITING && state != InviteState.RINGING
    }

    data class Target(val userId: String, val label: String, val avatarUrl: String? = null)

    private val _invites = MutableStateFlow<List<MeetInvite>>(emptyList())
    val invites: StateFlow<List<MeetInvite>> = _invites.asStateFlow()

    /** Latched the moment the FIRST invite of this room session goes out. */
    private val _upgraded = MutableStateFlow(false)
    val upgraded: StateFlow<Boolean> = _upgraded.asStateFlow()

    private val jobs = mutableMapOf<String, MutableList<Job>>()

    init {
        scope.launch { client.calls.collect { onFrame(it) } }
    }

    /**
     * Fan out escalation invites to the picked members. Latches [upgraded]
     * immediately (拍板: the inviter's UI upgrades on send, not on answer).
     */
    fun sendInvites(
        targets: List<Target>,
        media: String,
        roomSlug: String,
        roomName: String,
        /** "meet" (1:1 escalation / meeting pull — invitee logs the direct
         * chat) vs "group" (群语音 — only the group record; invitee must not
         * double-log). Relayed verbatim by jusi. */
        kind: String = "meet",
    ) {
        if (targets.isEmpty()) return
        _upgraded.value = true
        for (target in targets) {
            // One in-flight invite per person; a settled attempt can be re-sent
            // with a fresh call_id.
            if (_invites.value.any { it.userId == target.userId && !it.terminal }) continue
            val invite = MeetInvite(
                callId = UUID.randomUUID().toString(),
                userId = target.userId,
                label = target.label,
                avatarUrl = target.avatarUrl,
                state = InviteState.INVITING,
                media = media,
                kind = kind,
            )
            _invites.value = _invites.value + invite
            dispatch(invite, media, roomSlug, roomName, kind)
        }
    }

    /** Cancel every non-terminal invitee — the inviter is leaving the room
     * (拍板 #1: the invite dies with the inviter's presence). */
    fun cancelPending() {
        for (invite in _invites.value) {
            if (invite.terminal) continue
            sendFrame(invite, CallEvent.CANCEL, reason = "canceled")
            setState(invite.callId, InviteState.CANCELED)
        }
    }

    /** Full reset on room teardown — the next call session starts un-upgraded. */
    fun reset() {
        jobs.values.flatten().forEach { it.cancel() }
        jobs.clear()
        _upgraded.value = false
        _invites.value = emptyList()
    }

    // ---- internals ----

    private fun dispatch(
        invite: MeetInvite,
        media: String,
        roomSlug: String,
        roomName: String,
        kind: String,
    ) {
        scope.launch {
            val conv = try {
                // Idempotent create-or-get; the backend resolves the peer's IM
                // uid org-scoped so this module never handles raw subs.
                bridge.createDirectByUserId(invite.userId)
            } catch (e: Throwable) {
                Log.w(TAG, "meet-invite direct-conv resolve failed", e)
                setState(invite.callId, InviteState.FAILED)
                return@launch
            }
            val peerUid = conv.members.firstOrNull { it != conv.self_uid }
            if (peerUid == null) {
                setState(invite.callId, InviteState.FAILED)
                return@launch
            }
            val live = find(invite.callId) ?: return@launch
            if (live.terminal) return@launch // canceled while resolving
            val armed = live.copy(cid = conv.cid, peerUid = peerUid)
            replace(armed)

            if (!sendFrame(armed, CallEvent.INVITE, media = media, roomSlug = roomSlug, roomName = roomName, kind = kind)) {
                setState(invite.callId, InviteState.FAILED)
                return@launch
            }
            startTimers(armed, media, roomSlug, roomName, kind)
        }
    }

    private fun startTimers(
        invite: MeetInvite,
        media: String,
        roomSlug: String,
        roomName: String,
        kind: String,
    ) {
        val resend = scope.launch {
            repeat(INVITE_RESENDS) {
                delay(INVITE_RESEND_INTERVAL_MS)
                val live = find(invite.callId) ?: return@launch
                if (live.state != InviteState.INVITING) return@launch
                sendFrame(live, CallEvent.INVITE, media = media, roomSlug = roomSlug, roomName = roomName, kind = kind)
            }
        }
        val timeout = scope.launch {
            delay(RING_TIMEOUT_MS)
            val live = find(invite.callId) ?: return@launch
            if (live.terminal) return@launch
            sendFrame(live, CallEvent.CANCEL, reason = "timeout")
            setState(invite.callId, InviteState.TIMEOUT)
        }
        jobs.getOrPut(invite.callId) { mutableListOf() }.addAll(listOf(resend, timeout))
    }

    private fun onFrame(p: CallPayload) {
        val invite = find(p.callId) ?: return
        if (invite.peerUid == null || p.from != invite.peerUid) return
        when (p.event) {
            CallEvent.RINGING ->
                if (invite.state == InviteState.INVITING) setState(p.callId, InviteState.RINGING)
            // From ACCEPT on the room roster is the truth source; the chip is
            // only a transient "on their way" hint.
            CallEvent.ACCEPT -> setState(p.callId, InviteState.ACCEPTED)
            CallEvent.REJECT -> setState(p.callId, InviteState.REJECTED)
            CallEvent.BUSY -> setState(p.callId, InviteState.BUSY)
            // Only reaches us when offline push is NOT configured server-side —
            // with push, the invitee's phone is being woken instead.
            CallEvent.UNREACHABLE -> setState(p.callId, InviteState.UNREACHABLE)
        }
    }

    private fun sendFrame(
        invite: MeetInvite,
        event: String,
        reason: String? = null,
        media: String? = null,
        roomSlug: String? = null,
        roomName: String? = null,
        kind: String = "meet",
    ): Boolean {
        val cid = invite.cid ?: return false
        val to = invite.peerUid ?: return false
        return try {
            client.sendCall(
                CallPayload(
                    event = event,
                    callId = invite.callId,
                    cid = cid,
                    to = to,
                    media = media,
                    roomSlug = roomSlug,
                    roomName = roomName,
                    reason = reason,
                    kind = if (event == CallEvent.INVITE) kind else null,
                )
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "meet-invite sendCall($event) failed", e)
            false
        }
    }

    private fun find(callId: String): MeetInvite? = _invites.value.firstOrNull { it.callId == callId }

    private fun replace(invite: MeetInvite) {
        _invites.value = _invites.value.map { if (it.callId == invite.callId) invite else it }
    }

    private fun setState(callId: String, state: InviteState) {
        val invite = find(callId) ?: return
        if (invite.terminal) return
        replace(invite.copy(state = state))
        if (state != InviteState.INVITING && state != InviteState.RINGING) {
            jobs.remove(callId)?.forEach { it.cancel() }
            logMissedInvite(invite, state)
        }
    }

    /** P4-M3: 未接通的 meet 邀请落 A↔C「会议邀请」记录(与 Web 同口径)。 */
    private fun logMissedInvite(invite: MeetInvite, state: InviteState) {
        val result = when (state) {
            InviteState.TIMEOUT -> "missed"
            InviteState.REJECTED -> "declined"
            InviteState.BUSY -> "busy"
            InviteState.UNREACHABLE -> "unreachable"
            InviteState.CANCELED -> "canceled"
            else -> return // accepted=离房结算写 completed;failed 多半无 cid
        }
        val cid = invite.cid ?: return
        if (invite.kind == "group") return // 群语音只留群记录
        scope.launch {
            runCatching {
                client.sendText(
                    cid = cid,
                    body = org.json.JSONObject().apply {
                        put("media", invite.media)
                        put("result", result)
                        put("kind", "meet")
                    }.toString(),
                    contentType = "call-log",
                )
            }.onFailure { Log.w(TAG, "meet-invite miss-log send failed", it) }
        }
    }

    companion object {
        private const val TAG = "MeetInviteTracker"
        private const val RING_TIMEOUT_MS = 60_000L
        private const val INVITE_RESEND_INTERVAL_MS = 3_000L
        private const val INVITE_RESENDS = 3
    }
}
