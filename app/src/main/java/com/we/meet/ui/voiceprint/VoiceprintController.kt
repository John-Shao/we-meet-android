package com.we.meet.ui.voiceprint

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.*
import com.we.meet.data.voiceprint.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import retrofit2.HttpException

internal interface VoiceprintOperations {
    fun allowed(): Boolean
    suspend fun settings(): Result<VoiceprintSettingsDto>
    suspend fun samples(offset: Int): Result<VoiceprintPageDto<VoiceprintSampleDto>>
    suspend fun deletions(offset: Int): Result<VoiceprintPageDto<VoiceprintDeletionDto>>
    suspend fun sample(id: String): Result<VoiceprintSampleDto>
    suspend fun enrollment(id: String): Result<VoiceprintEnrollmentDto>
    suspend fun change(permission: VoiceprintPermission, enabled: Boolean, version: Int): Result<VoiceprintSettingsDto>
    suspend fun policy(enabled: Boolean, version: Int): Result<VoiceprintPolicyDto>
    suspend fun begin(version: Int, key: String, locale: String): Result<VoiceprintEnrollmentDto>
    suspend fun upload(enrollment: VoiceprintEnrollmentDto, slot: Int, bytes: ByteArray): Result<VoiceprintSampleDto>
    suspend fun audio(sample: VoiceprintSampleDto): Result<ByteArray>
    suspend fun decide(sample: VoiceprintSampleDto, accepted: Boolean, version: Int): Result<VoiceprintSampleDto>
    suspend fun remove(profile: String, version: Int, key: String): Result<VoiceprintDeletionDto>
}
internal class SessionVoiceprintOperations(private val client: VoiceprintSession) : VoiceprintOperations {
    override fun allowed() = client.allowed()
    override suspend fun settings() = client.settings()
    override suspend fun samples(offset: Int) = client.samples(offset)
    override suspend fun deletions(offset: Int) = client.deletions(offset)
    override suspend fun sample(id: String) = client.sample(id)
    override suspend fun enrollment(id: String) = client.enrollment(id)
    override suspend fun change(permission: VoiceprintPermission, enabled: Boolean, version: Int) = client.change(permission, enabled, version)
    override suspend fun policy(enabled: Boolean, version: Int) = client.policy(enabled, version)
    override suspend fun begin(version: Int, key: String, locale: String) = client.begin(version, key, locale)
    override suspend fun upload(enrollment: VoiceprintEnrollmentDto, slot: Int, bytes: ByteArray) = client.upload(enrollment, slot, bytes)
    override suspend fun audio(sample: VoiceprintSampleDto) = client.audio(sample)
    override suspend fun decide(sample: VoiceprintSampleDto, accepted: Boolean, version: Int) = client.decide(sample, accepted, version)
    override suspend fun remove(profile: String, version: Int, key: String) = client.remove(profile, version, key)
}

internal data class VoiceprintPreview(val sampleId: String, val bytes: ByteArray, val expiresAt: Long,
    val listened: Boolean = false, val selfConfirmed: Boolean = false)
internal enum class VoiceprintPhase { IDLE, REQUESTING, RECORDING }
internal enum class VoiceprintFailure { REQUEST, ACCESS, CONFLICT, MICROPHONE, DURATION, FORMAT, QUOTA, DUPLICATE, QUALITY, EXPIRED, STORAGE }
internal data class VoiceprintExternalAction(val enrollmentId: String, val version: Int, val generation: Int)
internal data class VoiceprintState(val settings: VoiceprintSettingsDto? = null,
    val samples: List<VoiceprintSampleDto> = emptyList(), val sampleOffset: Int? = null,
    val deletions: List<VoiceprintDeletionDto> = emptyList(), val deletionOffset: Int? = null,
    val enrollment: VoiceprintEnrollmentDto? = null, val clip: ByteArray? = null, val preview: VoiceprintPreview? = null,
    val busy: Boolean = false, val failure: VoiceprintFailure? = null, val conflict: Boolean = false,
    val phase: VoiceprintPhase = VoiceprintPhase.IDLE, val seconds: Int = 0, val deleteTarget: String? = null)

/** Private foreground state only. Source/version changes invalidate both audio and upload authority. */
internal class VoiceprintController(private val operations: VoiceprintOperations,
    private val stopPlayback: () -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(VoiceprintState())
    val state = mutable.asStateFlow()
    @Volatile private var active = true
    @Volatile private var foreground = true
    @Volatile private var epoch = 0
    private var firstPage = emptySet<String>()
    private var recording: VoiceprintRecording? = null
    private var startedAt = 0L
    private var beginCommand: Triple<String, Int, String>? = null
    private var uploadCommand: Upload? = null
    private var removal: Triple<String, Int, String>? = null
    private data class Upload(val enrollment: VoiceprintEnrollmentDto, val slot: Int, val bytes: ByteArray)
    fun allowed() = active && operations.allowed()
    private fun live() = allowed() && foreground
    private fun clearMedia() {
        recording?.cancel(); recording = null; stopPlayback()
        state.value.clip?.fill(0); state.value.preview?.bytes?.fill(0); uploadCommand = null
        mutable.value = state.value.copy(clip = null, preview = null, phase = VoiceprintPhase.IDLE, seconds = 0)
    }
    fun background() { foreground = false; epoch++; clearMedia(); beginCommand = null; removal = null; firstPage = emptySet(); mutable.value = VoiceprintState() }
    fun resume() { if (active) foreground = true }
    fun close() { background(); active = false }
    private fun fail(error: Throwable) {
        stopPlayback(); state.value.preview?.bytes?.fill(0)
        val status = (error as? HttpException)?.code()
        val code = runCatching { (error as? HttpException)?.response()?.errorBody()?.use { body ->
            val bytes = ByteArray(4096)
            try {
                var used = 0
                body.byteStream().use { stream ->
                    while (used < bytes.size) {
                        val read = stream.read(bytes, used, bytes.size - used)
                        if (read <= 0) break
                        used += read
                    }
                }
                Regex("\"code\"\\s*:\\s*\"([a-z_]{1,128})\"").find(String(bytes, 0, used, Charsets.UTF_8))?.groupValues?.get(1)
            } finally { bytes.fill(0) }
        } }.getOrNull()
        val failure = when {
            error is IdentityLoginChangedException || status in listOf(401, 403, 404, 410) -> VoiceprintFailure.ACCESS
            status == 409 -> VoiceprintFailure.CONFLICT
            error is VoiceprintAudioFormatException -> VoiceprintFailure.FORMAT
            error is VoiceprintDurationException -> VoiceprintFailure.DURATION
            code == "voiceprint_enrollment_quota" -> VoiceprintFailure.QUOTA
            code == "voiceprint_duplicate_audio" -> VoiceprintFailure.DUPLICATE
            code in listOf("voiceprint_audio_format_invalid", "voiceprint_wav_invalid") -> VoiceprintFailure.FORMAT
            code == "voiceprint_quality_pending" -> VoiceprintFailure.QUALITY
            code == "voiceprint_upload_expired" -> VoiceprintFailure.EXPIRED
            code == "voiceprint_key_unavailable" -> VoiceprintFailure.STORAGE
            else -> VoiceprintFailure.REQUEST
        }
        val conflict = failure in listOf(VoiceprintFailure.ACCESS, VoiceprintFailure.CONFLICT)
        if (conflict) { clearMedia(); beginCommand = null; removal = null }
        mutable.value = state.value.copy(preview = null, failure = failure, conflict = conflict || state.value.conflict,
            enrollment = if (conflict) null else state.value.enrollment,
            samples = if (failure == VoiceprintFailure.ACCESS) emptyList() else state.value.samples,
            settings = if (failure == VoiceprintFailure.ACCESS) null else state.value.settings)
    }
    private suspend fun run(action: suspend (Int) -> Unit) {
        if (!live() || state.value.busy || state.value.conflict) return
        val sequence = ++epoch
        mutable.value = state.value.copy(busy = true, failure = null)
        try { action(sequence) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (live() && sequence == epoch) fail(error) }
        finally { if (live() && sequence == epoch) mutable.value = state.value.copy(busy = false) }
    }
    private fun current(sequence: Int) = live() && sequence == epoch
    private fun settings() = requireNotNull(state.value.settings)
    private fun profileIds(value: VoiceprintSettingsDto) = value.profiles.filter { it.generation == value.generation && it.status != "deleted" }.map { it.id }.toSet()
    private fun changed(value: VoiceprintSettingsDto) = state.value.settings?.let {
        it.version != value.version || it.generation != value.generation || it.available != value.available
    } == true
    private suspend fun sync(sequence: Int, manual: Boolean = false) {
        val value = operations.settings().getOrThrow()
        val samples = operations.samples(0).getOrThrow()
        val deletions = operations.deletions(0).getOrThrow()
        if (!current(sequence)) return
        val ids = profileIds(value)
        require(samples.results.all { it.profileId in ids })
        val reset = changed(value)
        if (reset) { clearMedia(); beginCommand = null; removal = null; mutable.value = state.value.copy(enrollment = null) }
        val older = if (!reset && !manual && samples.nextOffset != null) state.value.samples.filter { it.id !in firstPage && it.profileId in ids } else emptyList()
        var rows = (samples.results + older).distinctBy { it.id }
        state.value.preview?.let { preview ->
            var sample = rows.find { it.id == preview.sampleId }
            if (sample != null && sample.id !in samples.results.map { it.id }) {
                val refreshed = operations.sample(sample.id)
                if (!current(sequence)) return
                if ((refreshed.exceptionOrNull() as? HttpException)?.code() == 404) { rows = rows.filter { it.id != preview.sampleId }; sample = null }
                else { sample = refreshed.getOrThrow(); require(sample.profileId in ids); rows = rows.map { if (it.id == sample.id) sample else it } }
            }
            if (sample?.audioAvailable != true || voiceprintExpired(sample.expiresAt, now())) {
                preview.bytes.fill(0); stopPlayback(); mutable.value = state.value.copy(preview = null)
            }
        }
        if (!current(sequence)) return
        val next = if (older.isNotEmpty() && state.value.sampleOffset != null) maxOf(state.value.sampleOffset!!, samples.nextOffset ?: 0) else samples.nextOffset
        firstPage = samples.results.map { it.id }.toSet()
        mutable.value = state.value.copy(settings = value, samples = rows, sampleOffset = next,
            deletions = deletions.results, deletionOffset = deletions.nextOffset)
        state.value.enrollment?.let { old ->
            val enrollment = operations.enrollment(old.id).getOrThrow()
            if (!current(sequence)) return
            if (enrollment.status in listOf("expired", "canceled") || enrollment.consentVersion != value.version || enrollment.generation != value.generation || voiceprintExpired(enrollment.expiresAt, now())) {
                clearMedia(); beginCommand = null; mutable.value = state.value.copy(enrollment = null)
            } else mutable.value = state.value.copy(enrollment = enrollment)
        }
    }
    suspend fun refresh(manual: Boolean = false) {
        if (!live()) return
        if (state.value.busy) {
            // A foreground recording still needs the five-second permission heartbeat.
            if (state.value.phase == VoiceprintPhase.RECORDING) {
                val sequence = epoch
                try {
                    val value = operations.settings().getOrThrow()
                    if (current(sequence) && changed(value)) {
                        clearMedia(); beginCommand = null
                        mutable.value = state.value.copy(settings = value, enrollment = null, samples = emptyList(), sampleOffset = null)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (current(sequence)) { clearMedia(); fail(error) } }
            }
            return
        }
        if (manual) mutable.value = state.value.copy(conflict = false)
        if (state.value.conflict) return
        run { sync(it, manual) }
    }
    suspend fun change(permission: VoiceprintPermission, enabled: Boolean) = run { sequence ->
        val value = settings(); if (enabled && !value.available) return@run
        clearMedia(); beginCommand = null; mutable.value = state.value.copy(enrollment = null)
        operations.change(permission, enabled, value.version).getOrThrow()
        if (current(sequence)) sync(sequence)
    }
    suspend fun policy(enabled: Boolean, version: Int) = run { sequence ->
        clearMedia(); mutable.value = state.value.copy(enrollment = null); beginCommand = null
        operations.policy(enabled, version).getOrThrow(); if (current(sequence)) sync(sequence)
    }
    suspend fun begin(locale: String) = run { sequence ->
        val value = settings(); if (!value.available || !value.allowEnrollment) return@run
        clearMedia()
        val command = beginCommand?.takeIf { it.second == value.version } ?: Triple(UUID.randomUUID().toString(), value.version, locale).also { beginCommand = it }
        val enrollment = operations.begin(command.second, command.first, command.third).getOrThrow()
        if (!current(sequence)) return@run
        require(enrollment.generation == value.generation && enrollment.consentVersion == value.version && enrollment.profileId != null)
        mutable.value = state.value.copy(enrollment = enrollment)
        sync(sequence)
    }
    fun slot(): Int = uploadCommand?.takeIf { it.bytes === state.value.clip && it.enrollment.id == state.value.enrollment?.id }?.slot
        ?: (0..5).firstOrNull { it !in state.value.enrollment?.uploadedSlots.orEmpty() } ?: -1
    fun canRecord(): Boolean {
        val value = state.value; val settings = value.settings ?: return false; val enrollment = value.enrollment ?: return false
        return live() && !value.conflict && settings.available && settings.allowEnrollment && enrollment.status == "open" &&
            enrollment.profileId in profileIds(settings) &&
            enrollment.consentVersion == settings.version && enrollment.generation == settings.generation && enrollment.uploadToken != null &&
            !voiceprintExpired(enrollment.expiresAt, now()) && slot() >= 0
    }
    fun externalAction(): VoiceprintExternalAction? = if (canRecord() && !state.value.busy) {
        val enrollment = requireNotNull(state.value.enrollment)
        VoiceprintExternalAction(enrollment.id, enrollment.consentVersion, enrollment.generation)
    } else null
    suspend fun restoreExternalAction(action: VoiceprintExternalAction): Boolean {
        run { sequence ->
            sync(sequence)
            if (!current(sequence)) return@run
            val value = settings()
            if (!value.available || !value.allowEnrollment || value.version != action.version || value.generation != action.generation) return@run
            val enrollment = operations.enrollment(action.enrollmentId).getOrThrow()
            if (!current(sequence)) return@run
            require(enrollment.id == action.enrollmentId && enrollment.consentVersion == action.version && enrollment.generation == action.generation)
            if (enrollment.status != "open" || enrollment.profileId !in profileIds(value) || voiceprintExpired(enrollment.expiresAt, now())) return@run
            clearMedia()
            mutable.value = state.value.copy(enrollment = enrollment)
        }
        return canRecord() && state.value.enrollment?.let { it.id == action.enrollmentId && it.consentVersion == action.version && it.generation == action.generation } == true
    }
    fun canUpload(): Boolean {
        val attempt = uploadCommand; val value = state.value; val settings = value.settings ?: return false
        return value.clip != null && if (attempt != null && attempt.bytes === value.clip) live() && !value.conflict && settings.available && settings.allowEnrollment &&
            attempt.enrollment.consentVersion == settings.version && attempt.enrollment.generation == settings.generation && !voiceprintExpired(attempt.enrollment.expiresAt, now()) else canRecord()
    }
    suspend fun file(bytes: ByteArray) = run { sequence ->
        if (!canRecord()) return@run
        withContext(Dispatchers.Default) { VoiceprintWave.duration(bytes) }
        if (!current(sequence) || !canRecord()) return@run
        clearMedia(); mutable.value = state.value.copy(clip = bytes.copyOf())
    }
    suspend fun record(input: VoiceprintRecording) = run { sequence ->
        if (!canRecord()) { input.cancel(); return@run }
        clearMedia(); recording = input; mutable.value = state.value.copy(phase = VoiceprintPhase.REQUESTING)
        try {
            val bytes = coroutineScope {
                input.capture({ current(sequence) && canRecord() }) {
                    launch {
                        if (current(sequence)) { startedAt = now(); mutable.value = state.value.copy(phase = VoiceprintPhase.RECORDING) }
                    }
                }
            }
            if (current(sequence) && canRecord()) mutable.value = state.value.copy(clip = bytes, phase = VoiceprintPhase.IDLE) else bytes.fill(0)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (current(sequence)) {
                fail(error)
                if (error !is VoiceprintAudioFormatException && error !is VoiceprintDurationException) mutable.value = state.value.copy(failure = VoiceprintFailure.MICROPHONE)
            }
        } finally {
            input.cancel()
            if (recording === input) recording = null
            if (current(sequence)) mutable.value = state.value.copy(phase = VoiceprintPhase.IDLE)
        }
    }
    fun finishRecording() { recording?.finish() }
    fun cancelRecording() { recording?.cancel() }
    fun discard() { if (live() && !state.value.busy) clearMedia() }
    fun endEnrollment() { if (live() && !state.value.busy) { clearMedia(); beginCommand = null; mutable.value = state.value.copy(enrollment = null) } }
    fun tick() {
        if (!allowed()) { close(); return }
        if (!foreground) return
        if (state.value.phase == VoiceprintPhase.RECORDING) mutable.value = state.value.copy(seconds = ((now() - startedAt) / 1000).toInt().coerceIn(0, 10))
        if (state.value.enrollment?.let { voiceprintExpired(it.expiresAt, now()) } == true) { clearMedia(); beginCommand = null; mutable.value = state.value.copy(enrollment = null) }
        if (state.value.preview?.expiresAt?.let { it <= now() } == true) { stopPlayback(); state.value.preview?.bytes?.fill(0); mutable.value = state.value.copy(preview = null) }
    }
    suspend fun upload() = run { sequence ->
        if (!canUpload()) return@run
        val attempt = uploadCommand ?: Upload(requireNotNull(state.value.enrollment), slot(), requireNotNull(state.value.clip)).also { uploadCommand = it }
        operations.upload(attempt.enrollment, attempt.slot, attempt.bytes).getOrThrow()
        if (current(sequence)) { clearMedia(); sync(sequence) }
    }
    suspend fun preview(sample: VoiceprintSampleDto) = run { sequence ->
        val current = state.value.samples.find { it.id == sample.id } ?: return@run
        if (!current.audioAvailable || voiceprintExpired(current.expiresAt, now())) return@run
        stopPlayback(); state.value.preview?.bytes?.fill(0); mutable.value = state.value.copy(preview = null)
        val bytes = operations.audio(current).getOrThrow()
        if (current(sequence) && state.value.samples.any { it.id == current.id && it.audioAvailable }) mutable.value = state.value.copy(preview = VoiceprintPreview(current.id, bytes, voiceprintDate(current.expiresAt))) else bytes.fill(0)
    }
    fun listened(id: String) { if (live() && state.value.preview?.sampleId == id) mutable.value = state.value.copy(preview = state.value.preview!!.copy(listened = true)) }
    fun confirmSelf(id: String, selected: Boolean) { if (live() && state.value.preview?.sampleId == id) mutable.value = state.value.copy(preview = state.value.preview!!.copy(selfConfirmed = selected)) }
    suspend fun decide(sample: VoiceprintSampleDto, accepted: Boolean) = run { sequence ->
        val current = state.value.samples.find { it.id == sample.id } ?: return@run
        val value = settings(); val preview = state.value.preview
        if (!value.allowEnrollment || !current.audioAvailable || voiceprintExpired(current.expiresAt, now())) return@run
        if (accepted && (!current.confirmable || preview?.sampleId != current.id || !preview.listened || !preview.selfConfirmed)) return@run
        clearMedia(); operations.decide(current, accepted, value.version).getOrThrow(); if (current(sequence)) sync(sequence)
    }
    fun requestRemoval(id: String?) {
        if (live() && !state.value.busy && !state.value.conflict && (id == null || state.value.settings?.profiles?.any { it.id == id && it.status != "deleted" } == true)) mutable.value = state.value.copy(deleteTarget = id)
    }
    suspend fun remove() = run { sequence ->
        val id = state.value.deleteTarget ?: return@run
        val command = removal?.takeIf { it.first == id } ?: Triple(id, settings().version, UUID.randomUUID().toString()).also { removal = it }
        clearMedia(); operations.remove(command.first, command.second, command.third).getOrThrow()
        if (current(sequence)) { beginCommand = null; removal = null; mutable.value = state.value.copy(deleteTarget = null, enrollment = null); sync(sequence) }
    }
    suspend fun more(samples: Boolean) = run { sequence ->
        if (samples) {
            val offset = state.value.sampleOffset ?: return@run
            val value = operations.settings().getOrThrow()
            if (!current(sequence)) return@run
            if (changed(value)) { sync(sequence); return@run }
            val page = operations.samples(offset).getOrThrow(); require(page.results.all { it.profileId in profileIds(value) })
            if (current(sequence)) mutable.value = state.value.copy(samples = (state.value.samples + page.results).distinctBy { it.id }, sampleOffset = page.nextOffset)
        } else {
            val offset = state.value.deletionOffset ?: return@run
            val page = operations.deletions(offset).getOrThrow()
            if (current(sequence)) mutable.value = state.value.copy(deletions = (state.value.deletions + page.results).distinctBy { it.id }, deletionOffset = page.nextOffset)
        }
    }
}
