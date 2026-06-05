package com.we.meet.data.repository

import com.we.meet.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Streaming room-AI client. Hits `POST /rooms/{id}/ask-ai-stream/` and
 * yields one [RoomAiEvent] per SSE frame. Cannot use Retrofit here —
 * Retrofit buffers the whole response body; SSE needs incremental reads
 * off the OkHttp BufferedSource.
 *
 * Auth: backend gates on `HasLiveKitRoomAccess` + `LiveKitTokenAuthentication`
 * so we send the LiveKit Bearer directly and stamp `No-Auth: 1` to
 * stop AuthInterceptor from overwriting it with the Keycloak token
 * (see [[reference-livekit-auth-chain]]).
 *
 * The returned Flow completes when the SSE stream closes or emits a
 * Done frame. An error frame surfaces as a RoomAiEvent.ErrorEvent and
 * the Flow then completes — the caller decides whether to retry. HTTP
 * non-2xx upstream errors throw on the first emission so callers can
 * treat them like normal failures (toast + reset state).
 */
class RoomAiRepository(
    private val baseHttp: OkHttpClient,
    moshi: Moshi = defaultMoshi,
) {
    /**
     * Reuse the app's OkHttp client but lengthen the read timeout —
     * SSE keeps the socket open between deltas which would otherwise
     * trip the default 30s readTimeout on the AI's first-token latency.
     */
    private val streamHttp: OkHttpClient by lazy {
        baseHttp.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private val requestAdapter = moshi.adapter(RoomAiAskRequest::class.java)
    private val eventAdapter = moshi.adapter(RoomAiRawEvent::class.java)

    fun askStream(
        roomId: String,
        livekitToken: String,
        question: String,
        history: List<RoomAiHistoryItem>,
    ): Flow<RoomAiEvent> = flow {
        val baseUrl = BuildConfig.WE_MEET_BASE_URL.let { if (it.endsWith("/")) it else "$it/" }
        val url = "${baseUrl}api/v1.0/rooms/$roomId/ask-ai-stream/"

        val body = requestAdapter
            .toJson(RoomAiAskRequest(question = question, history = history))
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer $livekitToken")
            .header("No-Auth", "1")
            .header("Accept", "text/event-stream")
            .build()

        streamHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RoomAiHttpException(response.code, response.body?.string().orEmpty())
            }
            val source = response.body?.source() ?: return@flow

            // SSE frames are delimited by a blank line; within a frame the
            // line `data: <json>` carries the payload. We accumulate lines
            // into [frameBuf] and flush on blank line.
            val frameBuf = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    val payload = frameBuf.toString()
                    frameBuf.clear()
                    val event = parseFrame(payload) ?: continue
                    emit(event)
                    if (event is RoomAiEvent.Done || event is RoomAiEvent.ErrorEvent) {
                        break
                    }
                } else {
                    if (frameBuf.isNotEmpty()) frameBuf.append('\n')
                    frameBuf.append(line)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun parseFrame(frame: String): RoomAiEvent? {
        // A frame can carry multiple lines (comments, event:, id:, data:)
        // but the backend only uses `data:` — pull that one out.
        val dataLine = frame.lineSequence()
            .firstOrNull { it.startsWith(DATA_PREFIX) }
            ?: return null
        val json = dataLine.substring(DATA_PREFIX.length).trim()
        if (json.isEmpty()) return null
        val raw = runCatching { eventAdapter.fromJson(json) }.getOrNull() ?: return null
        return when (raw.type) {
            "meta" -> RoomAiEvent.Meta(
                roomsReferenced = raw.rooms_referenced.orEmpty(),
                chunksUsed = raw.chunks_used ?: 0,
                modelUsed = raw.model_used.orEmpty(),
            )
            "delta" -> raw.text?.let { RoomAiEvent.Delta(it) }
            "done" -> RoomAiEvent.Done
            "error" -> RoomAiEvent.ErrorEvent(raw.message.orEmpty())
            else -> null
        }
    }

    companion object {
        private const val DATA_PREFIX = "data:"
        private val defaultMoshi: Moshi by lazy {
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        }
    }
}

@JsonClass(generateAdapter = true)
data class RoomAiAskRequest(
    val question: String,
    val history: List<RoomAiHistoryItem>,
)

@JsonClass(generateAdapter = true)
data class RoomAiHistoryItem(
    val role: String,
    val content: String,
)

/**
 * Internal SSE payload — backend keys are snake_case; we map to the
 * structured [RoomAiEvent] hierarchy via `type` after parsing.
 */
@JsonClass(generateAdapter = true)
internal data class RoomAiRawEvent(
    val type: String,
    val text: String? = null,
    val message: String? = null,
    val rooms_referenced: List<String>? = null,
    val chunks_used: Int? = null,
    val model_used: String? = null,
)

sealed interface RoomAiEvent {
    data class Meta(
        val roomsReferenced: List<String>,
        val chunksUsed: Int,
        val modelUsed: String,
    ) : RoomAiEvent

    data class Delta(val text: String) : RoomAiEvent

    data object Done : RoomAiEvent

    data class ErrorEvent(val message: String) : RoomAiEvent
}

class RoomAiHttpException(val code: Int, val body: String) :
    Exception("Room AI HTTP $code: $body")
