package com.we.meet.data.capture

import com.we.meet.data.api.DirectAsrCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.Closeable
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DirectAsrRow(val id: Int, val text: String, val startMs: Long, val endMs: Long) {
    override fun toString() = "DirectAsrRow(<private>)"
}

/** One task, bounded audio queue, explicit finish ACK; no retries or audio replay. */
class DirectAsrWire(private val onRows: (List<DirectAsrRow>) -> Unit) : DirectAsrConnection {
    private val ready = CompletableDeferred<Unit>()
    private val finished = CompletableDeferred<Unit>()
    private val task = UUID.randomUUID().toString()
    private var socket: WebSocket? = null
    private val rows = linkedMapOf<Int, DirectAsrRow>()
    private var ending = false
    private var closed = false
    private var inputMs = 0L
    private var textBytes = 0

    override suspend fun start(credentials: DirectAsrCredentials) {
        check(credentials.model == MODEL && credentials.token.startsWith("st-") &&
            credentials.expiresAt > System.currentTimeMillis() / 1000)
        val url = URI(credentials.url)
        check(url.scheme == "wss" && url.userInfo == null && url.query == null && url.fragment == null &&
            url.port == -1 && url.path == "/api-ws/v1/inference" &&
            Regex("[A-Za-z0-9-]+\\.(cn-beijing|ap-southeast-1)\\.maas\\.aliyuncs\\.com").matches(url.host ?: ""))
        open(client, Request.Builder().url(credentials.url).header("Authorization", "Bearer ${credentials.token}").build())
        withTimeout(15_000) { ready.await() }
    }

    internal suspend fun startForTest(client: OkHttpClient, request: Request) {
        open(client, request)
        withTimeout(5000) { ready.await() }
    }

    @Synchronized private fun open(client: OkHttpClient, request: Request) {
        check(socket == null && !closed)
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(command("run-task").put("payload", JSONObject()
                    .put("task_group", "audio").put("task", "asr").put("function", "recognition")
                    .put("model", MODEL).put("input", JSONObject()).put("parameters", JSONObject()
                        .put("format", "pcm").put("sample_rate", 16000).put("heartbeat", true))).toString())) fail()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { receive(text) }.onFailure { fail() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); fail() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { if (!finished.isCompleted) fail(); webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (!finished.isCompleted) fail() }
        })
    }

    @Synchronized private fun receive(text: String) {
        if (closed) return
        check(text.length <= 65536)
        val event = JSONObject(text)
        val header = event.getJSONObject("header")
        check(header.getString("task_id") == task)
        when (header.getString("event")) {
            "task-started" -> { check(!ready.isCompleted); ready.complete(Unit) }
            "result-generated" -> {
                check(ready.isCompleted)
                val sentence = event.optJSONObject("payload")?.optJSONObject("output")?.optJSONObject("sentence") ?: return
                if (sentence.optBoolean("heartbeat") || !sentence.optBoolean("sentence_end")) return
                val id = sentence.getInt("sentence_id")
                val row = DirectAsrRow(id, sentence.getString("text"), sentence.getLong("begin_time"), sentence.getLong("end_time"))
                check(id >= 0 && row.text.length <= 10000 && row.startMs >= 0 && row.endMs >= row.startMs && row.endMs <= inputMs + 250)
                if (row.text.isBlank()) return
                rows[id]?.let { check(it == row); return }
                textBytes += row.text.toByteArray(Charsets.UTF_8).size
                check(rows.size < 20000 && textBytes <= 4000000)
                rows[id] = row; onRows(rows.values.toList())
            }
            "task-finished" -> { check(ending); finished.complete(Unit) }
            else -> error("ASR protocol failure")
        }
    }

    @Synchronized override fun send(pcm: ByteArray): Boolean {
        if (closed || ending || !ready.isCompleted || pcm.isEmpty() || pcm.size % 32 != 0 || inputMs + pcm.size / 32 > 43200000 || socket!!.queueSize() > 64000) return false
        inputMs += pcm.size / 32
        return socket!!.send(pcm.toByteString())
    }

    override suspend fun finish(): List<DirectAsrRow> {
        synchronized(this) {
            check(!closed)
            if (!ending) { ending = true; check(socket!!.send(command("finish-task").toString())) }
        }
        withTimeout(15_000) { finished.await() }
        return synchronized(this) { rows.values.toList() }
    }

    private fun command(action: String) = JSONObject().put("header", JSONObject()
        .put("action", action).put("task_id", task).put("streaming", "duplex")).put("payload", JSONObject().put("input", JSONObject()))

    @Synchronized private fun fail() {
        val error = IllegalStateException("Direct ASR failed")
        ready.completeExceptionally(error); finished.completeExceptionally(error); socket?.cancel()
    }
    @Synchronized override fun close() { closed = true; socket?.cancel(); socket = null; ready.cancel(); finished.cancel() }
    companion object {
        const val MODEL = "qwen-audio-3.1-asr-flash-streaming"
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS).build()
    }
}
