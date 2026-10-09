package com.we.meet.ui.ai

import android.content.Context
import com.we.meet.data.api.AssistantTranslationDirectSession
import com.we.meet.feature.assistant.aicall.rtc.AoqDataConnection
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.Closeable

/** Both transports expose model events; WebRTC adapts PCM append events to RTP. */
internal interface BilingualModelConnection : Closeable {
    val events: Channel<JSONObject>
    suspend fun connect(allocate: suspend (String?) -> AssistantTranslationDirectSession)
    suspend fun send(event: JSONObject)
}

internal class AoqBilingualConnection(context: Context, detection: Boolean, reverse: Boolean = false) : BilingualModelConnection {
    private val connection = AoqDataConnection(context, detection, reverse)
    override val events get() = connection.events
    override suspend fun connect(allocate: suspend (String?) -> AssistantTranslationDirectSession) {
        connection.connect(checkNotNull(allocate(null).aoq))
    }
    override suspend fun send(event: JSONObject) = connection.send(event)
    override fun close() = connection.close()
}
