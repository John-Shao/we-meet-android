package com.we.meet.feature.assistant.aicall.rtc

import android.content.*
import android.os.*
import com.we.meet.feature.assistant.aicall.model.AoqCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.json.JSONArray
import java.io.Closeable
import java.util.UUID

/** Bound, private IPC. Credentials live only in memory and never in an Intent. */
class AoqDataConnection(private val context: Context, private val languageDetection: Boolean, private val reverseTranslation: Boolean = false) : Closeable {
    private val connected = CompletableDeferred<Unit>()
    val events = Channel<JSONObject>(256)
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false
    private val pending = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private var sequence = 0
    private var allocation: AoqCredentials? = null
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (closed) return
            runCatching {
                when (msg.what) {
                    1 -> connected.complete(Unit)
                    2 -> check(events.trySend(JSONObject(msg.data.getString("text")!!)).isSuccess)
                    3 -> { android.util.Log.w("AoqDataConnection", "worker_failure=${msg.data.getString("text")}"); error("AOQ connection failed") }
                    4 -> pending.remove(msg.data.getString("text")!!.toInt())?.complete(Unit)
                }
            }.onFailure { fail() }
        }
    })
    private val binding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            remote = Messenger(binder)
            val value = checkNotNull(allocation)
            allocation = null
            val json = JSONObject().put("sid", value.sid).put("aoqTokenForClient", value.aoqTokenForClient)
                .put("clientRelayCertFingerprint", value.clientRelayCertFingerprint).put("workspaceIdHash", value.workspaceIdHash)
                .put("clientRelayEndpoints", JSONArray(value.clientRelayEndpoints.mapIndexed { index, relay ->
                    JSONObject().put("endpoint", relay.endpoint).put("port", relay.port).put("route_index", relay.route_index ?: index)
                }))
            runCatching { remote!!.send(Message.obtain(null, 1).apply {
                replyTo = incoming; data = Bundle().apply { putString("allocation", json.toString()) }
            }) }.onFailure { fail() }
        }
        override fun onServiceDisconnected(name: ComponentName) = fail()
        override fun onBindingDied(name: ComponentName) = fail()
        override fun onNullBinding(name: ComponentName) = fail()
    }
    suspend fun connect(credentials: AoqCredentials) = withContext(Dispatchers.Main.immediate) {
        check(!closed && !bound)
        allocation = credentials
        val service = if (languageDetection) AoqLanguageService::class.java
            else if (reverseTranslation) AoqReverseTranslationService::class.java else AoqDataService::class.java
        bound = context.bindService(Intent(context, service), binding, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        check(bound)
        withTimeout(25_000) { connected.await() }
    }
    suspend fun send(event: JSONObject) = withContext(Dispatchers.Main.immediate) {
        check(!closed)
        if (!event.has("event_id")) event.put("event_id", UUID.randomUUID().toString())
        val id = ++sequence
        val ack = CompletableDeferred<Unit>()
        pending[id] = ack
        try {
            remote!!.send(Message.obtain(null, 2, id, 0).apply { data = Bundle().apply { putString("text", event.toString()) } })
            withTimeout(5000) { ack.await() }
        } finally { pending.remove(id) }
    }
    private fun fail() {
        val error = IllegalStateException("AOQ connection failed")
        connected.completeExceptionally(error); events.close(error)
        pending.values.forEach { it.completeExceptionally(error) }
    }
    override fun close() {
        if (closed) return
        closed = true; allocation = null
        runCatching { remote?.send(Message.obtain(null, 3)) }
        if (bound) runCatching { context.unbindService(binding) }
        bound = false; remote = null; connected.cancel(); events.cancel()
        pending.values.forEach { it.cancel() }; pending.clear()
    }
}
