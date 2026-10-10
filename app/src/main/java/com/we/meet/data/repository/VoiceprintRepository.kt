package com.we.meet.data.repository

import com.we.meet.data.api.VoiceprintApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import com.we.meet.data.voiceprint.VoiceprintWave
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

enum class VoiceprintPermission(val wireName: String) {
    ENROLLMENT("allow_enrollment"), ACCUMULATION("allow_accumulation"), IDENTIFICATION("allow_identification");
    fun enabled(value: VoiceprintSettingsDto) = when (this) {
        ENROLLMENT -> value.allowEnrollment; ACCUMULATION -> value.allowAccumulation; IDENTIFICATION -> value.allowIdentification
    }
}

/** A client belongs to one actual login and scope. Biometric content stays outside application caches. */
class VoiceprintRepository(private val api: VoiceprintApi, private val currentViewer: () -> String?, private val currentSession: () -> String) {
    fun open(viewer: String, organization: String? = null): VoiceprintSession {
        identityUuid(viewer); organization?.let(::identityUuid)
        require(currentViewer() == viewer)
        return VoiceprintSession(api, viewer, organization, currentSession()) { owner, session -> currentViewer() == owner && currentSession() == session }
    }
}

class VoiceprintSession internal constructor(private val api: VoiceprintApi, val owner: String, val organization: String?,
    private val session: String, private val valid: (String, String) -> Boolean) {
    fun allowed() = valid(owner, session)
    private suspend fun <T> request(action: suspend (PrivateLogin) -> T): Result<T> = try {
        if (!allowed()) throw IdentityLoginChangedException()
        val value = withTimeout(15000) { if (!allowed()) throw IdentityLoginChangedException(); action(PrivateLogin(session)) }
        if (!allowed()) {
            if (value is ByteArray) value.fill(0)
            throw IdentityLoginChangedException()
        }
        Result.success(value)
    } catch (_: TimeoutCancellationException) { Result.failure(IdentityRequestTimeoutException()) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { Result.failure(error) }

    suspend fun scopes(offset: Int = 0) = request { login ->
        pageOffset(offset)
        api.scopes(owner, login, offset).also { page ->
            checkPage(page.results.map { it.id }, page.nextOffset, offset)
            page.results.forEach { require(it.name.isNotBlank() && it.name.length <= 512); require(it.policy.version >= 0) }
        }
    }
    suspend fun settings() = request { api.settings(owner, it, organization).also(::checkSettings) }
    suspend fun change(permission: VoiceprintPermission, enabled: Boolean, version: Int) = request { login ->
        require(version >= 0)
        api.change(owner, login, mapOf("organization_id" to organization, "expected_version" to version, permission.wireName to enabled)).also {
            checkSettings(it); require(it.version >= version && permission.enabled(it) == enabled)
        }
    }
    suspend fun policy(enabled: Boolean, version: Int) = request { login ->
        require(version >= 0)
        api.policy(requireNotNull(organization), owner, login, VoiceprintPolicyChangeDto(enabled, version)).also {
            require(it.version >= version && it.enabled == enabled)
        }
    }
    suspend fun begin(version: Int, key: String, locale: String) = request { login ->
        require(version > 0); identityUuid(key); require(locale in listOf("zh-CN", "en", "fr", "de", "nl"))
        api.begin(owner, login, VoiceprintEnrollmentRequestDto(organization, version, key, locale)).also {
            checkEnrollment(it); require(it.consentVersion == version)
        }
    }
    suspend fun enrollment(id: String) = request { login ->
        identityUuid(id); api.enrollment(id, owner, login).also { checkEnrollment(it); require(it.id == id) }
    }
    suspend fun upload(enrollment: VoiceprintEnrollmentDto, slot: Int, wav: ByteArray) = request { login ->
        checkEnrollment(enrollment); require(slot in 0..5 && enrollment.uploadToken != null && !voiceprintExpired(enrollment.expiresAt))
        val duration = VoiceprintWave.duration(wav)
        // Copy before dispatch so another owner cannot mutate an in-flight request.
        val payload = wav.copyOf()
        try {
            api.upload(enrollment.id, slot, owner, login, requireNotNull(enrollment.uploadToken), payload.toRequestBody("audio/wav".toMediaType())).also {
                checkSample(it); require(it.profileId == enrollment.profileId && it.durationMs == duration && it.sourceType == "enrollment")
            }
        } finally { payload.fill(0) }
    }
    suspend fun samples(offset: Int = 0) = request { login ->
        pageOffset(offset); api.samples(owner, login, organization, offset).also {
            checkPage(it.results.map { row -> row.id }, it.nextOffset, offset); it.results.forEach(::checkSample)
        }
    }
    suspend fun sample(id: String) = request { login ->
        identityUuid(id); api.sample(id, owner, login, organization).also { checkSample(it); require(it.id == id) }
    }
    suspend fun audio(sample: VoiceprintSampleDto) = request { login ->
        checkSample(sample)
        val current = api.sample(sample.id, owner, login, organization).also(::checkSample)
        require(current.id == sample.id && current.profileId == sample.profileId && current.audioAvailable && !voiceprintExpired(current.expiresAt))
        if (!allowed()) throw IdentityLoginChangedException()
        api.audio(sample.id, owner, login).use { body ->
            require(body.contentType()?.type == "audio" && body.contentType()?.subtype == "wav")
            require(body.contentLength() == -1L || body.contentLength() in 44..VoiceprintWave.MAX_BYTES.toLong())
            withContext(Dispatchers.IO) { body.byteStream().use(VoiceprintWave::read) }
        }
    }
    suspend fun decide(sample: VoiceprintSampleDto, accepted: Boolean, version: Int) = request { login ->
        checkSample(sample); require(version > 0 && sample.audioAvailable && !voiceprintExpired(sample.expiresAt) &&
            sample.status in listOf("pending", "processing", "quality_pending", "ready") && (!accepted || sample.confirmable))
        api.decide(sample.id, owner, login, VoiceprintDecisionDto(accepted, version)).also {
            checkSample(it); require(it.id == sample.id && it.profileId == sample.profileId && it.status == if (accepted) "confirmed" else "rejected")
        }
    }
    suspend fun remove(profile: String, version: Int, key: String) = request { login ->
        identityUuid(profile); identityUuid(key); require(version > 0)
        api.remove(profile, owner, login, VoiceprintRemovalDto(version, key)).also(::checkDeletion)
    }
    suspend fun deletions(offset: Int = 0) = request { login ->
        pageOffset(offset); api.deletions(owner, login, organization, offset).also {
            checkPage(it.results.map { row -> row.id }, it.nextOffset, offset); it.results.forEach(::checkDeletion)
        }
    }
    private fun checkSettings(value: VoiceprintSettingsDto) {
        require(value.organizationId == organization && value.version >= 0 && value.generation >= 0)
        require(value.displayState == null || value.displayState in VOICEPRINT_DISPLAY_STATES)
        require(value.profiles.map { it.id }.distinct().size == value.profiles.size)
        value.profiles.forEach {
            identityUuid(it.id); require(it.generation > 0 && it.status in listOf("pending", "active", "paused", "deleted"))
            it.confirmedAt?.let(::voiceprintDate); it.lastUpdatedAt?.let(::voiceprintDate)
            require(it.displayState == null || it.displayState in VOICEPRINT_DISPLAY_STATES)
            require(it.updateReasons.distinct().size == it.updateReasons.size && it.updateReasons.all { reason -> reason in VOICEPRINT_UPDATE_REASONS })
            require(it.effectiveDeviceGroups.size <= 5 && it.effectiveDeviceGroups.distinct().size == it.effectiveDeviceGroups.size && it.effectiveDeviceGroups.all { group -> group == "default" })
            require(if (it.displayState == "needs_update") it.updateReasons.isNotEmpty() else it.updateReasons.isEmpty())
            require(if (it.displayState == "established") it.effectiveDeviceGroups.isNotEmpty() && it.status == "active" && it.generation == value.generation && it.confirmedAt != null && it.lastUpdatedAt != null && value.available else it.effectiveDeviceGroups.isEmpty())
        }
        require(value.displayState != "established" || value.profiles.any { it.displayState == "established" })
    }
    private fun checkEnrollment(value: VoiceprintEnrollmentDto) {
        identityUuid(value.id); value.profileId?.let(::identityUuid)
        require(value.organizationId == organization && value.consentVersion > 0 && value.generation > 0)
        require(value.status in listOf("open", "closed", "expired", "canceled")); voiceprintDate(value.expiresAt)
        require(value.maxClips == 6 && value.challenges.size == 6 && value.challenges.all { it.isNotBlank() && it.length <= 1000 })
        require(value.uploadedSlots.size <= 6 && value.uploadedSlots.distinct().size == value.uploadedSlots.size && value.uploadedSlots.all { it in 0..5 })
        require(value.sampleRate == 24000 && value.channels == 1 && value.format == "pcm16_wav" && value.clipDuration == VoiceprintDurationDto(3000, 10000))
        require(value.uploadToken == null || Regex("[A-Za-z0-9_-]{43}").matches(value.uploadToken))
    }
}

private val VOICEPRINT_DISPLAY_STATES = setOf("not_enabled", "collecting", "awaiting_confirmation", "established", "needs_update", "paused", "deleting", "deleted")
private val VOICEPRINT_UPDATE_REASONS = setOf("expired", "model_changed", "contributions_changed", "storage_unavailable")

internal fun voiceprintDate(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()
internal fun voiceprintExpired(value: String, now: Long = System.currentTimeMillis()) = voiceprintDate(value) <= now
private fun pageOffset(offset: Int) = require(offset in 0..10000)
private fun checkPage(ids: List<String>, next: Int?, offset: Int) {
    require(ids.size <= 25 && ids.distinct().size == ids.size); ids.forEach(::identityUuid)
    require(next == null || (ids.isNotEmpty() && next in offset + 1..10000))
}
private fun checkSample(value: VoiceprintSampleDto) {
    identityUuid(value.id); identityUuid(value.profileId); voiceprintDate(value.expiresAt)
    require(value.status in listOf("pending", "processing", "quality_pending", "ready", "confirmed", "rejected", "expired", "deleted"))
    require(value.sourceType in listOf("enrollment", "call") && value.durationMs in 3000..10000)
    require(!value.confirmable || value.status == "ready" && value.audioAvailable)
    require(value.status !in listOf("rejected", "expired", "deleted") || !value.audioAvailable && !value.confirmable)
}
private fun checkDeletion(value: VoiceprintDeletionDto) {
    identityUuid(value.id); require(value.status in listOf("queued", "running", "succeeded", "failed") && value.revokedGeneration > 0)
    value.finishedAt?.let(::voiceprintDate)
    require(value.errorCode == null || Regex("[a-z][a-z0-9_]{0,127}").matches(value.errorCode))
}
