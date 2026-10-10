package com.we.meet.ui.voiceprint

import com.we.meet.data.api.dto.VoiceprintCallConnectionDto
import com.we.meet.data.api.dto.VoiceprintCallControlDto
import com.we.meet.data.api.dto.VoiceprintSettingsDto
import com.we.meet.data.repository.VoiceprintCallSession
import com.we.meet.data.repository.IdentityLoginChangedException
import com.we.meet.data.repository.voiceprintDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

internal interface VoiceprintCallOperations {
    fun allowed(): Boolean
    suspend fun capability(): Result<Boolean>
    suspend fun read(): Result<VoiceprintCallConnectionDto>
    suspend fun declare(snapshot: VoiceprintCallConnectionDto, paused: Boolean, shared: Boolean, device: String): Result<VoiceprintCallControlDto>
    suspend fun disableAccumulation(snapshot: VoiceprintCallConnectionDto): Result<VoiceprintSettingsDto>
}
internal class SessionVoiceprintCallOperations(private val client: VoiceprintCallSession) : VoiceprintCallOperations {
    override fun allowed() = client.allowed()
    override suspend fun capability() = client.capability()
    override suspend fun read() = client.read()
    override suspend fun declare(snapshot: VoiceprintCallConnectionDto, paused: Boolean, shared: Boolean, device: String) = client.declare(snapshot, paused, shared, device)
    override suspend fun disableAccumulation(snapshot: VoiceprintCallConnectionDto) = client.disableAccumulation(snapshot)
}
internal enum class VoiceprintCallFailure { UNAVAILABLE, CONFLICT, LOGIN_CHANGED, CONNECTION_PENDING }
internal data class VoiceprintCallState(val snapshot: VoiceprintCallConnectionDto? = null,
    val enabled: Boolean = false, val loading: Boolean = false, val busy: Boolean = false,
    val deviceChanged: Boolean = false, val inputObservable: Boolean = false,
    val phase: String = "unavailable", val failure: VoiceprintCallFailure? = null)

/** One controller per frozen login and exact RTC occurrence, confined to the UI dispatcher. */
internal class VoiceprintCallController(private val operations: VoiceprintCallOperations,
    private val scope: CoroutineScope, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(VoiceprintCallState())
    val state = mutable.asStateFlow()
    private var active = true
    private var foreground = false
    private var epoch = 0
    private var requestedAt = 0L
    private var capabilityAt: Long? = null
    private var reading: Job? = null
    private var writing: Job? = null
    fun allowed() = active && operations.allowed()
    private fun current(sequence: Int) = allowed() && foreground && sequence == epoch
    fun start(): Job? { if (!allowed()) return null; foreground = true; return refresh() }
    fun close() {
        active = false; foreground = false; epoch++; reading?.cancel(); writing?.cancel()
        reading = null; writing = null; mutable.value = VoiceprintCallState()
    }
    private fun failure(error: Throwable, sequence: Int) {
        if (!active || !foreground || sequence != epoch) return
        val kind = when {
            !operations.allowed() || error is IdentityLoginChangedException -> VoiceprintCallFailure.LOGIN_CHANGED
            (error as? HttpException)?.code() == 409 -> VoiceprintCallFailure.CONFLICT
            (error as? HttpException)?.code() == 404 -> VoiceprintCallFailure.CONNECTION_PENDING
            else -> VoiceprintCallFailure.UNAVAILABLE
        }
        mutable.value = state.value.copy(snapshot = null, loading = false, busy = false, failure = kind)
        tick()
    }
    fun refresh(): Job? {
        if (!allowed() || !foreground || reading?.isActive == true || state.value.busy) return null
        val sequence = ++epoch
        mutable.value = state.value.copy(loading = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val checked = capabilityAt
                if (checked == null || now() - checked !in 0..29999) {
                    val enabled = operations.capability().getOrThrow()
                    if (!current(sequence)) return@launch
                    capabilityAt = now()
                    mutable.value = state.value.copy(enabled = enabled)
                }
                if (!state.value.enabled) { mutable.value = state.value.copy(snapshot = null, loading = false, busy = false); tick(); return@launch }
                val started = now()
                val value = operations.read().getOrThrow()
                if (current(sequence)) {
                    requestedAt = started; mutable.value = state.value.copy(snapshot = value, failure = null)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error, sequence) }
            finally {
                if (sequence == epoch) { reading = null; mutable.value = state.value.copy(loading = false); tick(); resetDevice() }
            }
        }
        reading = job; job.start(); return job
    }
    fun declare(paused: Boolean, shared: Boolean, device: String): Job? {
        if (state.value.deviceChanged || !paused && !state.value.inputObservable) return null
        return mutate { operations.declare(it, paused, shared, device).getOrThrow() }
    }
    fun disableAccumulation() = mutate { operations.disableAccumulation(it).getOrThrow() }
    private fun mutate(isReset: Boolean = false, action: suspend (VoiceprintCallConnectionDto) -> Unit): Job? {
        val previous = state.value.snapshot ?: return null
        if (!allowed() || !foreground || state.value.busy) return null
        reading?.cancel(); reading = null
        val sequence = ++epoch
        mutable.value = state.value.copy(busy = true, failure = null); tick()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action(previous)
                if (!current(sequence)) { if (!operations.allowed()) failure(IdentityLoginChangedException(), sequence); return@launch }
                val started = now()
                val value = operations.read().getOrThrow()
                if (current(sequence)) {
                    requestedAt = started; mutable.value = state.value.copy(snapshot = value, failure = null)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error, sequence) }
            finally {
                if (sequence == epoch) {
                    writing = null; mutable.value = state.value.copy(busy = false, loading = false)
                    if (state.value.deviceChanged && safelyUndeclared()) mutable.value = state.value.copy(deviceChanged = false)
                    tick()
                    // A failed reset clears snapshot: only a later successful read may retry it.
                    if (!isReset) resetDevice()
                }
            }
        }
        writing = job; job.start(); return job
    }
    fun invalidateDevice() {
        if (!allowed()) return
        mutable.value = state.value.copy(deviceChanged = true); tick(); resetDevice()
    }
    fun inputObservationAvailable(available: Boolean) {
        mutable.value = state.value.copy(inputObservable = available)
        if (!available) invalidateDevice() else tick()
    }
    private fun safelyUndeclared() = state.value.snapshot?.control?.let { it.paused && it.sharedMicrophone && it.deviceGroup.isEmpty() } == true
    private fun resetDevice() {
        if (!state.value.deviceChanged || state.value.busy || !foreground || !allowed()) return
        if (safelyUndeclared()) { mutable.value = state.value.copy(deviceChanged = false); tick(); return }
        mutate(isReset = true) { operations.declare(it, true, true, "").getOrThrow() }
    }
    /** Stop best effort before clearing private state; returning requires a fresh device declaration. */
    suspend fun background() {
        val previous = state.value.snapshot
        foreground = false; epoch++
        reading?.cancelAndJoin(); writing?.cancelAndJoin(); reading = null; writing = null
        capabilityAt = null
        mutable.value = VoiceprintCallState(deviceChanged = true)
        if (active && operations.allowed() && previous != null)
            operations.declare(previous, true, true, "")
    }
    fun tick() {
        val value = state.value
        val snapshot = value.snapshot
        val phase = when {
            !allowed() || !foreground || !value.inputObservable -> "unavailable"
            value.deviceChanged -> "device_changed"
            value.busy -> "changing"
            snapshot == null || now() - requestedAt !in 0..14999 -> "unavailable"
            snapshot.control.runtime.state in setOf("sampling", "uploading") -> {
                val updated = snapshot.control.runtime.updatedAt?.let(::voiceprintDate)
                val age = updated?.let { voiceprintDate(snapshot.observedAt) - it + now() - requestedAt }
                if (age == null || age !in 0..4999) "waiting" else snapshot.control.runtime.state
            }
            else -> snapshot.control.runtime.state
        }
        if (phase != value.phase) mutable.value = state.value.copy(phase = phase)
        if (!operations.allowed() && state.value.snapshot != null) mutable.value = state.value.copy(snapshot = null, failure = VoiceprintCallFailure.LOGIN_CHANGED)
    }
}
