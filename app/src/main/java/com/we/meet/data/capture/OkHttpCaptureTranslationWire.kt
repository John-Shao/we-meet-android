package com.we.meet.data.capture

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Dedicated credential-free socket client; the single-use ticket goes only in the first frame. */
class OkHttpCaptureTranslationWire private constructor(url: String, listener: CaptureTranslationWire.Listener) : CaptureTranslationWire {
    private val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = listener.opened()
        override fun onMessage(webSocket: WebSocket, text: String) = listener.message(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { listener.failed(); webSocket.cancel() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); listener.failed() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.failed()
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { listener.failed(); webSocket.close(code, null) }
    })
    override val queuedBytes get() = socket.queueSize()
    override fun send(text: String) = socket.send(text)
    override fun send(pcm: ByteArray) = socket.send(pcm.toByteString())
    override fun close() { socket.cancel() }
    override fun toString() = "CaptureTranslationWire(<private>)"
    companion object {
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build()
        fun open(url: String, listener: CaptureTranslationWire.Listener): CaptureTranslationWire = OkHttpCaptureTranslationWire(url, listener)
    }
}
