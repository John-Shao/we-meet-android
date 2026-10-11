package com.we.meet.data.repository

import com.we.meet.data.api.SpeakerIdentificationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class IdentityLoginChangedException : IllegalStateException("authentication_changed")
class IdentityRequestTimeoutException : IllegalStateException("voiceprint_request_timeout")

/** The application's only retained commands contain IDs, never candidate names or credentials. */
class SpeakerIdentificationRepository(
    private val api: SpeakerIdentificationApi,
    private val currentViewer: () -> String?,
    private val currentSession: () -> String,
) {
    private val pending = mutableMapOf<String, IdentitySubmissionDto>()
    private var pendingSession: String? = null
    fun open(viewer: String, record: String): IdentificationSession {
        identityUuid(viewer); identityUuid(record)
        require(viewer == currentViewer())
        return IdentificationSession(this, viewer, record, currentSession())
    }
    @Synchronized internal fun allowed(viewer: String, session: String): Boolean {
        val current = currentSession()
        if (pendingSession != current) { pending.clear(); pendingSession = current }
        return currentViewer() == viewer && current == session
    }
    @Synchronized internal fun intent(client: IdentificationSession): IdentitySubmissionDto? {
        resetIntents(client)
        return pending["${client.viewerId}:${client.recordId}"]?.let { it.copy(userIds = it.userIds.toList(), speakerIds = it.speakerIds.toList()) }
    }
    @Synchronized internal fun remember(client: IdentificationSession, body: IdentitySubmissionDto) {
        resetIntents(client); validateIdentitySubmission(body)
        val key = "${client.viewerId}:${client.recordId}"
        require(pending.containsKey(key) || pending.size < 20)
        require(pending[key] == null || pending[key] == body) { "voiceprint_pending_request_exists" }
        pending[key] = body.copy(userIds = body.userIds.toList(), speakerIds = body.speakerIds.toList())
    }
    @Synchronized internal fun acknowledge(client: IdentificationSession, key: String) {
        resetIntents(client)
        val identifier = "${client.viewerId}:${client.recordId}"
        if (pending[identifier]?.requestKey == key) pending.remove(identifier)
    }
    private fun resetIntents(client: IdentificationSession) {
        val session = currentSession()
        if (pendingSession != session) { pending.clear(); pendingSession = session }
        if (!allowed(client.viewerId, client.session)) throw IdentityLoginChangedException()
    }
    internal suspend fun <T> request(client: IdentificationSession, operation: suspend (SpeakerIdentificationApi, PrivateLogin) -> T): Result<T> = try {
        if (!client.allowed()) throw IdentityLoginChangedException()
        val value = withTimeout(15_000) {
            if (!client.allowed()) throw IdentityLoginChangedException()
            operation(api, PrivateLogin(client.session))
        }
        if (!client.allowed()) throw IdentityLoginChangedException()
        Result.success(value)
    } catch (_: TimeoutCancellationException) { Result.failure(IdentityRequestTimeoutException()) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { Result.failure(error) }
}

class IdentificationSession internal constructor(
    private val repository: SpeakerIdentificationRepository,
    val viewerId: String, val recordId: String, internal val session: String,
) {
    fun allowed() = repository.allowed(viewerId, session)
    fun pending() = repository.intent(this)
    fun remember(body: IdentitySubmissionDto) = repository.remember(this, body)
    fun acknowledge(key: String) = repository.acknowledge(this, key)
    suspend fun enabled() = repository.request(this) { api, login ->
        api.config(login).speakerIdentity?.let { it.enabled && it.matchingEnabled } ?: false
    }
    suspend fun options(revision: Int, offset: Int = 0) = repository.request(this) { api, login ->
        identityDirectory(revision, offset)
        api.options(recordId, viewerId, login, revision, offset).also {
            require(it.recordRevision == revision)
            it.requiredOrganizationId?.let(::identityUuid)
            require(it.personalAllowed == (it.requiredOrganizationId == null))
            identityIds(it.targets.map { target -> target.id }, emptyAllowed = true)
            it.targets.forEach { target -> identityName(target.name) }
            identityPage(it.scopes.results.map { scope -> scope.id }, it.scopes.nextOffset, offset)
            it.scopes.results.forEach { scope ->
                identityName(scope.name)
                require(it.requiredOrganizationId == null || scope.id == it.requiredOrganizationId)
            }
        }
    }
    suspend fun candidates(organization: String?, revision: Int, query: String = "", offset: Int = 0) = repository.request(this) { api, login ->
        identityDirectory(revision, offset); organization?.let(::identityUuid); require(query.length <= 80)
        api.candidates(recordId, viewerId, login, organization ?: "personal", revision, query, offset).also {
            require(it.recordRevision == revision && it.organizationId == organization)
            identityPage(it.results.map { person -> person.id }, it.nextOffset, offset)
            it.results.forEach { person -> identityName(person.name); require(organization != null || person.id == viewerId) }
        }
    }
    suspend fun read(key: String? = null) = repository.request(this) { api, login ->
        key?.let(::identityUuid)
        api.read(recordId, viewerId, login, key).also { validateIdentityResponse(it, key) }
    }
    suspend fun submit(body: IdentitySubmissionDto) = repository.request(this) { api, login ->
        validateIdentitySubmission(body)
        api.submit(recordId, viewerId, login, body).also {
            validateIdentityResponse(it, body.requestKey)
            val batch = requireNotNull(it.request)
            require(batch.organizationId == body.organizationId && batch.sourceRevision == body.expectedRevision)
            require(batch.jobs.map { job -> job.speakerId }.toSet() == body.speakerIds.toSet())
        }
    }
    suspend fun cancel(key: String, revision: Int) = repository.request(this) { api, login ->
        identityUuid(key); require(revision > 0)
        api.cancel(recordId, viewerId, login, IdentityCancellationDto(key, revision)).also { validateIdentityResponse(it, key) }
    }
    suspend fun decide(speaker: String, suggestion: IdentitySuggestionDto, confirm: Boolean, revision: Int) = repository.request(this) { api, login ->
        identityUuid(speaker); identityUuid(suggestion.id); require(revision > 0)
        require(suggestion.state == "pending" && (!confirm || suggestion.canConfirm))
        api.decide(recordId, speaker, viewerId, login, IdentitySuggestionDecisionDto(
            if (confirm) "confirm_suggestion" else "reject_suggestion", suggestion.id, revision)).also {
            require(it.id == speaker && requireNotNull(it.recordRevision) >= revision)
            identityName(requireNotNull(it.displayName))
            if (confirm) require(it.attributionKind == "member" && it.attributedUserId == requireNotNull(suggestion.candidate).id && it.manualLabel.isEmpty())
        }
    }
}

internal fun identityUuid(value: String) = require(Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}").matches(value))
private fun identityName(value: String) = require(value.isNotBlank() && value.length <= 512)
private fun identityDirectory(revision: Int, offset: Int) = require(revision > 0 && offset in 0..10000)
private fun identityIds(ids: List<String>, emptyAllowed: Boolean = false) {
    require(ids.size in (if (emptyAllowed) 0 else 1)..50 && ids.distinct().size == ids.size)
    ids.forEach(::identityUuid)
}
private fun identityPage(ids: List<String>, next: Int?, offset: Int) {
    require(ids.size <= 25); identityIds(ids, emptyAllowed = true)
    require(next == null || next in offset + 1..10000)
}
internal fun validateIdentitySubmission(value: IdentitySubmissionDto) {
    identityUuid(value.requestKey); require(value.expectedRevision > 0)
    value.organizationId?.let(::identityUuid); identityIds(value.userIds); identityIds(value.speakerIds)
}
internal fun validateIdentityResponse(value: IdentityResponseDto, key: String? = null) {
    require(value.recordRevision > 0)
    val batch = value.request ?: run { require(key == null); return }
    identityUuid(batch.id); identityUuid(batch.requestKey)
    require(key == null || batch.requestKey == key)
    batch.organizationId?.let(::identityUuid)
    require(batch.sourceRevision in 1..value.recordRevision)
    OffsetDateTime.parse(batch.createdAt)
    identityIds(batch.jobs.map { it.id }); identityIds(batch.jobs.map { it.speakerId })
    require(batch.jobs.mapNotNull { it.suggestion?.id }.distinct().size == batch.jobs.count { it.suggestion != null })
    batch.jobs.forEach { job ->
        require(job.status in listOf("queued", "running", "succeeded", "failed", "canceled", "expired"))
        job.suggestion?.let {
            identityUuid(it.id)
            require(it.state in listOf("pending", "confirmed", "rejected", "invalidated"))
            require(it.result in listOf("suggested", "unknown", "mixed_speaker", "ambiguous", "insufficient_audio", "unavailable"))
            require(Regex("[a-z][a-z0-9_]{0,127}").matches(it.reason))
            require(it.clipCount in 0..12 && it.speechMs in 0..it.clipCount * 10000L && it.queryIntervals.size <= it.clipCount)
            var end = 0L
            it.queryIntervals.forEach { interval ->
                require(interval.startMs in end..7200000 && interval.endMs in interval.startMs + 3000..minOf(7200000, interval.startMs + 10000))
                end = interval.endMs
            }
            it.candidate?.let { candidate -> identityUuid(candidate.id); identityName(candidate.name) }
            require(!it.canConfirm || (!batch.processing && it.state == "pending" && it.result == "suggested" && it.candidate != null && job.status == "succeeded"))
            require(it.candidate == null || (it.state == "pending" && it.result == "suggested" && job.status == "succeeded"))
            require(!(it.verificationUnavailable || it.state != "pending") || (it.candidate == null && !it.canConfirm && it.queryIntervals.isEmpty()))
        }
    }
}
