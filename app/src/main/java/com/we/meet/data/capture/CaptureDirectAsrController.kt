package com.we.meet.data.capture

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.*
import com.we.meet.data.api.dto.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class DirectAsrViewState(val phase: String = "idle", val rows: List<DirectAsrFinal> = emptyList(),
    val gap: Boolean = false, val pending: Boolean = false) {
    override fun toString() = "DirectAsrViewState(<private>)"
}

/** One account and recording. Provider IO follows the microphone FGS, never the screen. */
class CaptureDirectAsrController(private val journal: DirectAsrJournal, private val api: CaptureDirectAsrApi,
    private val credentials: AssistantTranscriptionApi, private val transcription: CaptureTranscriptionApi,
    private val scope: CoroutineScope, private val current: () -> Boolean,
    private val observe: (String) -> CapturePcmTap.Subscription,
    private val newWire: ((List<DirectAsrRow>) -> Unit) -> DirectAsrConnection = { DirectAsrWire(it) }) : Closeable {
    private val mutex = Mutex()
    private val uploads = Mutex()
    private val mutable = MutableStateFlow(DirectAsrViewState())
    val state = mutable.asStateFlow()
    @Volatile private var local: DirectAsrLocal? = null
    @Volatile private var turn: Job? = null
    @Volatile private var socket: DirectAsrConnection? = null
    @Volatile private var tap: CapturePcmTap.Subscription? = null
    private val end = AtomicLong()
    @Volatile private var turnOrigin: Long? = null
    @Volatile private var closed = false
    private val saving = AtomicBoolean()
    private val stopRequested = AtomicBoolean()
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(DirectAsrStart::class.java).serializeNulls()
    private fun authorized() = !closed && current()
    private fun snapshot(row: DirectAsrLocal): DirectAsrLocal {
        val origin = turnOrigin ?: return row
        if (row.ranges.lastOrNull()?.startMs != origin) return row
        return row.copy(ranges = row.ranges.dropLast(1) + DirectAsrRange(origin, end.get()))
    }
    private fun display(row: DirectAsrLocal) {
        mutable.value = DirectAsrViewState(row.phase, journal.rows(row.localId, limit=20, newest=true), row.gap, row.sequence > row.acknowledged)
    }
    suspend fun recover(id: String) = mutex.withLock {
        check(authorized())
        local = journal.get(id)?.let { row ->
            if(row.phase in setOf("running", "connecting", "preparing")) row.copy(phase="error", gap=true).also(journal::save) else row
        }
        local?.let(::display)
        if(local == null) mutable.value = DirectAsrViewState()
    }
    @Synchronized fun start(capture: LocalCapture) {
        if (!authorized() || turn?.isActive == true || saving.get()) return
        if(local?.localId == capture.id && local?.phase == "saved") return
        stopRequested.set(false)
        turn = scope.launch { runTurn(capture) }
    }
    fun resume(capture: LocalCapture) {
        if (local?.localId == capture.id && local?.phase == "paused") start(capture)
    }
    private suspend fun runTurn(capture: LocalCapture) {
        val events = Channel<DirectAsrRow>(64)
        var collector: Job? = null
        var flusher: Job? = null
        var complete = false
        var consumer: CapturePcmTap.Subscription? = null
        var wire: DirectAsrConnection? = null
        try {
            check(authorized())
            val remote = requireNotNull(capture.remote)
            val server = transcription.state(remote.id)
            check(server.directAvailable)
            mutex.withLock {
                if(local?.localId != capture.id || local?.phase == "saved") {
                    check(server.results.firstOrNull()?.status !in setOf("queued", "running"))
                    local = DirectAsrLocal(capture.id, remote.id, capture.create.deviceId, capture.create.leaseKey, expected=server.results.firstOrNull()?.id)
                    journal.save(requireNotNull(local))
                }
                val row = requireNotNull(local)
                check(row.capture == remote.id && row.device == capture.create.deviceId && row.lease == capture.create.leaseKey)
                local = row.copy(phase="connecting").also { journal.save(it); display(it) }
            }
            ensureJob()
            flush(false)
            check(!stopRequested.get())
            val token = credentials.session()
            check(!stopRequested.get())
            var count = 0
            wire = newWire { rows ->
                for (row in rows.drop(count)) check(events.trySend(row).isSuccess)
                count = rows.size
            }
            socket = wire
            wire.start(token)
            check(authorized() && !stopRequested.get())
            consumer = observe(remote.id)
            tap = consumer
            if(stopRequested.get()) consumer.finish()
            val origin = consumer.startMs
            turnOrigin = origin; end.set(origin)
            mutex.withLock {
                val row = requireNotNull(local)
                check(row.ranges.size < 50 && origin >= (row.ranges.lastOrNull()?.endMs ?: 0))
                local = row.copy(phase="running", ranges=row.ranges + DirectAsrRange(origin, origin),
                    gap=row.gap || row.ranges.lastOrNull()?.let { origin > it.endMs } == true).also { journal.save(it); display(it) }
            }
            collector = scope.launch {
                for(row in events) mutex.withLock {
                    check(authorized())
                    val value = snapshot(requireNotNull(local))
                    val to = minOf(origin + row.endMs, end.get())
                    require(to >= origin + row.startMs)
                    val final = DirectAsrFinal(UUID.randomUUID().toString(), value.sequence + 1, origin + row.startMs, to, row.text)
                    local = journal.append(value, final).also(::display)
                }
            }
            flusher = scope.launch {
                while(isActive && authorized()) {
                    delay(2000)
                    runCatching { flush(false) }.onFailure {
                        if(it is CancellationException) throw it
                        if(it is retrofit2.HttpException && it.code() in setOf(403,404,409)) { socket?.close(); tap?.close() }
                        mutex.withLock { local?.let { row -> local = snapshot(row).copy(gap=true).also { journal.save(it); display(it) } } }
                    }
                }
            }
            while(currentCoroutineContext().isActive && authorized()) {
                collector.ensureActive()
                val frame = consumer.poll()
                if(frame != null) frame.use {
                    val bytes = ByteBuffer.allocate(it.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(it.samples)
                    check(wire.send(bytes.array()))
                    end.addAndGet(it.samples.size / 16L)
                } else when(consumer.state) {
                    CapturePcmTap.State.RUNNING -> delay(10)
                    CapturePcmTap.State.FINISHED -> break
                    else -> error("Direct ASR source ended")
                }
            }
            check(authorized())
            wire.finish()
            complete = true
        } catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { complete = false }
        finally {
            withContext(NonCancellable) {
                flusher?.cancelAndJoin()
                consumer?.close(); wire?.close(); tap = null; socket = null
                events.close()
                collector?.join()
                complete = complete && collector?.isCancelled != true
                if(authorized()) mutex.withLock {
                    local?.let { row ->
                        val value = snapshot(row)
                        local = value.copy(phase=if(complete) "paused" else "error", gap=value.gap || !complete,
                            ranges=value.ranges.filter { it.endMs > it.startMs }).also { journal.save(it); display(it) }
                    }
                }
                turnOrigin = null
            }
        }
    }
    /** Finish only this provider task; a subsequent user microphone resume starts future audio. */
    suspend fun pause() {
        stopRequested.set(true)
        tap?.finish()
        turn?.join()
        if(authorized()) runCatching { flush(false) }.onFailure { if(it is CancellationException) throw it }
    }
    /** Reconcile a lost allocation receipt using the persisted key, without opening a model. */
    private suspend fun ensureJob() {
        val before = mutex.withLock { requireNotNull(local) }
        if(before.job != null) return
        val body = adapter.toJson(DirectAsrStart(before.device, before.expected)).toRequestBody("application/json".toMediaType())
        val created = api.start(before.capture, before.lease, before.key, body)
        check(authorized())
        mutex.withLock { local = requireNotNull(local).copy(job=created.job.id).also(journal::save) }
    }
    suspend fun save() {
        if(!authorized() || local == null || local?.phase == "saved" || !saving.compareAndSet(false, true)) return
        try {
            pause()
            ensureJob()
            mutex.withLock { local?.let { local = it.copy(phase="saving").also { row -> journal.save(row); display(row) } } }
            flush(true)
        } catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) {
            if(authorized()) mutex.withLock { local?.let { local = it.copy(phase="save_error").also { row -> journal.save(row); display(row) } } }
        } finally { saving.set(false) }
    }
    private suspend fun flush(finish: Boolean) = uploads.withLock {
        while(authorized()) {
            val value = mutex.withLock {
                local?.let { row -> snapshot(row).also { local = it; journal.save(it) } }
            } ?: return@withLock
            val job = value.job ?: return@withLock
            val rows = journal.rows(value.localId, value.acknowledged)
            val ranges = value.ranges.filter { it.endMs > it.startMs }
            val receipt = api.sync(value.capture, job, value.lease, DirectAsrSync(value.device, "sync", ranges, rows, value.sequence, !value.gap))
            check(authorized())
            mutex.withLock {
                val row = requireNotNull(local)
                require(receipt.id == row.job && receipt.finalCount >= row.acknowledged && receipt.finalCount <= row.sequence)
                local = snapshot(row).copy(acknowledged=receipt.finalCount).also { journal.save(it); display(it) }
            }
            if(rows.isEmpty() || receipt.finalCount >= value.sequence) break
        }
        if(finish && authorized()) {
            val row = mutex.withLock { requireNotNull(local) }
            val job = requireNotNull(row.job)
            require(row.sequence == row.acknowledged)
            api.sync(row.capture, job, row.lease, DirectAsrSync(row.device, "finish", row.ranges, emptyList(), row.sequence, !row.gap))
            check(authorized())
            mutex.withLock { local = row.copy(phase="saved").also { journal.save(it); display(it) } }
        }
    }
    override fun close() { closed = true; socket?.close(); tap?.close(); turn?.cancel() }
}
