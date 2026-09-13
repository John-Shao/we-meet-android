package com.we.meet.data.capture

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.data.repository.CaptureTranslationSource
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptureTranslationViewState(
    val remote: CaptureTranslationStateDto? = null,
    val live: CaptureTranslationLiveState? = null,
    val pending: MeetingIntent? = null,
    val busy: Boolean = false,
    val storageError: Boolean = false,
    val failed: Boolean = false,
    val muted: Boolean = false,
) { override fun toString() = "CaptureTranslationViewState(<private>)" }

/** Lifetime belongs to one resumed UI/capture revision. Store ownership remains with the caller. */
class CaptureTranslationController(
    private val source: CaptureTranslationSource,
    private val revision: Long,
    private val repository: CaptureTranslationRepository,
    store: MeetingIntentStore,
    private val current: () -> Boolean,
    private val recording: () -> Boolean,
    private val observe: () -> CapturePcmTap.Subscription,
    private val output: ((() -> Unit) -> CaptureTranslationOutput),
    private val wire: (String, CaptureTranslationWire.Listener) -> CaptureTranslationWire = OkHttpCaptureTranslationWire::open,
) : Closeable {
    private val alive = AtomicBoolean(true)
    private val busy = AtomicBoolean(false)
    private val operations = AtomicLong()
    private val coordinator = CaptureTranslationCoordinator(source, store, repository)
    private val mutable = MutableStateFlow(CaptureTranslationViewState())
    val state = mutable.asStateFlow()
    @Volatile private var socket: CaptureTranslationSocket? = null
    @Volatile private var sound: CaptureTranslationOutput? = null
    private fun active() = alive.get() && runCatching(current).getOrDefault(false)
    private fun capturing() = active() && runCatching(recording).getOrDefault(false)

    suspend fun load() {
        try { val pending = coordinator.pending(); if (active()) mutable.update { it.copy(pending = pending, storageError = false) } }
        catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) { if (active()) mutable.update { it.copy(storageError = true) } }
        refresh()
    }
    suspend fun refresh() {
        if (!active() || busy.get()) return
        val serial = operations.get()
        val result = repository.state(source)
        if (!active() || serial != operations.get()) return
        val value = result.getOrNull()
        if (value == null) {
            disconnect()
            mutable.update { it.copy(remote = null, live = null, failed = true) }
        } else {
            mutable.update { it.copy(remote = value) }
            socket?.let { consumer ->
                if (value.current?.id != consumer.run.id || value.current.generation != consumer.run.generation || value.source.revision != revision || value.current.status !in CaptureTranslationRepository.activeStates) {
                    if (consumer.state.phase !in terminal) disconnect()
                } else if (value.current.status == "stopping") consumer.finish()
            }
        }
    }
    suspend fun perform(operation: String, configuration: CaptureTranslationChoiceDto? = null, recover: Boolean = false) {
        val before = state.value
        if (!active() || before.storageError || !busy.compareAndSet(false, true)) return
        if (!recover && (before.pending != null || if (operation == "start") !capturing() || before.remote?.canStart != true || configuration == null || configuration.saveTranslations && before.remote.canSaveTranslations != true else before.remote?.canStop != true)) { busy.set(false); return }
        val serial = operations.incrementAndGet(); mutable.update { it.copy(busy = true, failed = false) }
        var candidate: CaptureTranslationOutput? = null
        val outputFailed = AtomicBoolean(false)
        try {
            val request = if (recover) requireNotNull(CaptureTranslationRepository.requestAdapter.fromJson(requireNotNull(before.pending).body))
                else CaptureTranslationRequestDto(source.device, operation, revision, before.remote?.current?.id, configuration)
            if (!recover && request.operation == "start" && request.configuration?.audio == true) {
                candidate = output { outputFailed.set(true); if (operations.get() == serial) { disconnect(); if (active()) mutable.update { it.copy(failed = true) } } }
                candidate.open()
            }
            check(active() && !outputFailed.get())
            val receipt = coordinator.control(request)
            check(active())
            mutable.update { it.copy(remote = receipt.current) }
            val run = receipt.current.current
            if (!recover && !receipt.replayed && request.operation == "start" && run?.id == receipt.command.result.id && run.status == "starting" && capturing() && !outputFailed.get()) {
                val ticket = repository.ticket(source, run).getOrThrow()
                check(capturing() && !outputFailed.get())
                disconnect(); sound = candidate; candidate = null
                mutable.update { it.copy(muted = false) }
                val consumer = CaptureTranslationSocket(source, run, ticket, { capturing() && operations.get() == serial && !outputFailed.get() }, observe, wire,
                    { live -> if (active() && operations.get() == serial) {
                        mutable.update { it.copy(live = live) }
                        if (live.phase in terminal) { val output = sound; sound = null; output?.close() }
                    } }, { samples -> sound?.play(samples) })
                socket = consumer; mutable.update { it.copy(live = consumer.state) }; consumer.connect()
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) { disconnect(); if (active()) mutable.update { it.copy(failed = true, remote = null) } }
        finally {
            try {
                candidate?.close()
                if (active()) {
                    try { val pending = coordinator.pending(); mutable.update { it.copy(pending = pending) } }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { mutable.update { it.copy(storageError = true) } }
                }
            } finally {
                busy.set(false)
                if (active()) mutable.update { it.copy(busy = false) }
            }
        }
    }
    fun begin(direction: String) { if (capturing()) socket?.begin(direction) }
    fun endTurn() { if (active()) socket?.endTurn() }
    fun finishConnected(): Boolean {
        val consumer = socket ?: return false
        if (!active() || consumer.state.phase in terminal + "connecting") { disconnect(); return false }
        consumer.finish(); return true
    }
    fun mute(value: Boolean) {
        if (!active()) return
        try { sound?.mute(value); mutable.update { it.copy(muted = value) } }
        catch (_: Exception) { disconnect(); mutable.update { it.copy(failed = true) } }
    }
    private fun disconnect() {
        val consumer = socket; socket = null
        val output = sound; sound = null
        try { consumer?.close() } finally { output?.close() }
    }
    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        operations.incrementAndGet(); disconnect(); mutable.value = CaptureTranslationViewState()
    }
    companion object { val terminal = setOf("stopped", "incomplete", "unknown") }
}
