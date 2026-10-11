package com.we.meet.data.repository

import com.we.meet.data.api.VoiceprintApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal val VOICEPRINT_CALL_DEVICES = setOf("", "headset", "handset", "computer")
private val CONTROLS = setOf("ready", "disabled", "disconnected", "source_removed", "authorization_required", "paused", "shared_microphone", "device_required")
private val RUNTIMES = setOf("stopped", "waiting", "starting", "sampling", "uploading", "unavailable")
private val REASONS = CONTROLS + setOf("", "microphone_unavailable", "quota_exhausted", "sampling_dispatch_unavailable")

/** Ordinary owner login and trusted RTC occurrence only; no join token, cache or media. */
class VoiceprintCallSession internal constructor(private val api: VoiceprintApi, val owner: String,
    val roomSid: String, val participantSid: String, private val login: String,
    private val valid: (String, String) -> Boolean) {
    init { identityUuid(owner); rtcSid(roomSid); rtcSid(participantSid) }
    private var bound = false
    private var sessionId: String? = null
    private var organizationId: String? = null
    fun allowed() = valid(owner, login)
    private suspend fun <T> request(action: suspend (PrivateLogin) -> T): Result<T> = try {
        if (!allowed()) throw IdentityLoginChangedException()
        val value = withTimeout(15000) {
            if (!allowed()) throw IdentityLoginChangedException()
            action(PrivateLogin(login))
        }
        if (!allowed()) throw IdentityLoginChangedException()
        Result.success(value)
    } catch (_: TimeoutCancellationException) { Result.failure(IdentityRequestTimeoutException()) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { Result.failure(error) }

    suspend fun capability() = request { api.samplingConfiguration(owner, it).speakerIdentity?.let { flags -> flags.enabled && flags.samplingEnabled } ?: false }
    suspend fun read() = request { api.samplingConnection(owner, it, roomSid, participantSid).also(::checkConnection) }
    suspend fun declare(snapshot: VoiceprintCallConnectionDto, paused: Boolean, shared: Boolean, device: String) = request { tag ->
        checkSnapshot(snapshot); require(device in VOICEPRINT_CALL_DEVICES)
        api.samplingDeclaration(owner, tag, VoiceprintCallDeclarationDto(snapshot.control.sessionId, participantSid,
            snapshot.control.revision, paused, shared, device)).also { checkControl(it); require(it.sessionId == snapshot.control.sessionId) }
    }
    suspend fun disableAccumulation(snapshot: VoiceprintCallConnectionDto): Result<VoiceprintSettingsDto> {
        return try {
            checkSnapshot(snapshot)
            VoiceprintSession(api, owner, snapshot.organizationId, login, valid)
                .change(VoiceprintPermission.ACCUMULATION, false, snapshot.permission.version)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }
    @Synchronized private fun checkSnapshot(value: VoiceprintCallConnectionDto) {
        require(bound && value.roomSid == roomSid && value.control.sessionId == sessionId && value.organizationId == organizationId)
        require(value.permission.version >= 0); checkControl(value.control)
    }
    @Synchronized private fun checkConnection(value: VoiceprintCallConnectionDto) {
        require(value.roomSid == roomSid)
        value.organizationId?.let(::identityUuid)
        require(if (value.organizationId == null) value.organizationName == null else value.organizationName != null && value.organizationName.length <= 255)
        val observed = voiceprintDate(value.observedAt)
        require(value.limits.clipMs in 3000..10000 && value.limits.sessionMs in 3000..60000 && value.limits.dailyMs in 3000..120000 && value.limits.candidateRetentionSeconds in 1..86400)
        require(value.permission.version >= 0); checkControl(value.control)
        require(!bound || value.control.sessionId == sessionId && value.organizationId == organizationId)
        if (value.control.runtime.state in setOf("sampling", "uploading")) {
            require(value.permission.available && value.permission.allowAccumulation && value.control.state == "ready")
            val updated = voiceprintDate(requireNotNull(value.control.runtime.updatedAt))
            require(observed - updated in 0..4999)
        }
        bound = true; sessionId = value.control.sessionId; organizationId = value.organizationId
    }
    private fun checkControl(value: VoiceprintCallControlDto) {
        identityUuid(value.sessionId); require(value.participantSid == participantSid && value.revision >= 0)
        require(value.deviceGroup in VOICEPRINT_CALL_DEVICES && value.state in CONTROLS && value.stopReason in setOf("", "mixed_speaker"))
        require(value.runtime.state in RUNTIMES && value.runtime.reason in REASONS)
        value.runtime.updatedAt?.let(::voiceprintDate)
        require(value.state != "ready" || !value.paused && !value.sharedMicrophone && value.deviceGroup.isNotEmpty())
        require(value.state == "ready" || value.runtime.state == "stopped")
        value.runtime.remainingMs?.let { require(it.sessionMs in 0..60000 && it.dailyMs in 0..120000) }
    }
}
private fun rtcSid(value: String) = require(Regex("[A-Za-z0-9_-]{1,64}").matches(value))
