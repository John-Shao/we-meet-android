package com.we.meet.data

import com.we.meet.data.api.DirectAsrCredentials
import com.we.meet.data.capture.DirectAsrRow
import com.we.meet.data.capture.DirectAsrWire
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectAsrWireTest {
    @Test fun liveDirectAsrWithDedicatedTemporaryToken() = runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as com.we.meet.WeMeetApp
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        app.authRepository.sendOtp(arguments.getString("e2ePhone") ?: "13800000009").getOrThrow()
        app.authRepository.verifyOtp(arguments.getString("e2ePhone") ?: "13800000009", arguments.getString("e2eOtp") ?: "123456").getOrThrow()
        val credentials = app.apiClient.assistantTranscriptionApi.session()
        assertEquals(DirectAsrWire.MODEL, credentials.model)
        assertTrue(credentials.token.startsWith("st-"))
        val pcm = instrumentation.context.assets.open("aoq-english.pcm").use { it.readBytes() }
        val wire = DirectAsrWire {}
        try {
            wire.start(credentials)
            for (offset in pcm.indices step 3200) {
                val frame = ByteArray(3200)
                pcm.copyInto(frame, 0, offset, minOf(offset + frame.size, pcm.size))
                assertTrue(wire.send(frame))
                kotlinx.coroutines.delay(100)
            }
            val rows = wire.finish()
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.sumOf { it.text.length } > 10)
        } finally { wire.close() }
    }
    @Test fun finishWaitsForTailAndDeduplicatesFinals() = runBlocking {
        val server = MockWebServer()
        var input = 0
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            var task = ""
            fun event(type: String) = JSONObject().put("header", JSONObject().put("event", type).put("task_id", task))
            override fun onMessage(socket: WebSocket, text: String) {
                val message = JSONObject(text)
                task = message.getJSONObject("header").getString("task_id")
                if (message.getJSONObject("header").getString("action") == "run-task") {
                    assertEquals(DirectAsrWire.MODEL, message.getJSONObject("payload").getString("model"))
                    socket.send(event("task-started").toString())
                } else {
                    assertEquals(3200, input)
                    val sentence = JSONObject().put("sentence_id", 0).put("sentence_end", true)
                        .put("text", "Final sentence.").put("begin_time", 0).put("end_time", 100)
                    val final = event("result-generated").put("payload", JSONObject()
                        .put("output", JSONObject().put("sentence", sentence))).toString()
                    socket.send(final); socket.send(final); socket.send(event("task-finished").toString())
                }
            }
            override fun onMessage(socket: WebSocket, bytes: ByteString) { input += bytes.size }
        }))
        server.start()
        var rows = emptyList<DirectAsrRow>()
        val wire = DirectAsrWire { rows = it }
        try {
            wire.startForTest(OkHttpClient(), Request.Builder().url(server.url("/")).build())
            assertTrue(wire.send(ByteArray(3200)))
            wire.finish()
            assertEquals("Final sentence.", rows.single().text)
            assertFalse(wire.send(ByteArray(3200)))
        } finally { wire.close(); server.shutdown() }
    }

    @Test fun prematureFinishIsNotSuccess() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                val task = JSONObject(text).getJSONObject("header").getString("task_id")
                socket.send(JSONObject().put("header", JSONObject().put("task_id", task).put("event", "task-finished")).toString())
            }
        }))
        server.start()
        val wire = DirectAsrWire {}
        try {
            assertTrue(runCatching { wire.startForTest(OkHttpClient(), Request.Builder().url(server.url("/")).build()) }.isFailure)
        } finally { wire.close(); server.shutdown() }
    }

    @Test fun credentialsArePrivateAndRejectUntrustedEndpoints() = runBlocking {
        val credential = DirectAsrCredentials(DirectAsrWire.MODEL, "wss://untrusted.invalid/api-ws/v1/inference", "st-private", Long.MAX_VALUE)
        assertFalse(credential.toString().contains("st-private"))
        val wire = DirectAsrWire {}
        try { assertTrue(runCatching { wire.start(credential) }.isFailure) }
        finally { wire.close() }
    }
}
