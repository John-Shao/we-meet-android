package com.we.meet.data.api

import android.util.Log
import com.we.meet.feature.im.ui.search.AskCitation
import com.we.meet.feature.im.ui.search.AskEvent
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

private const val TAG = "GlobalAskSse"

/**
 * P1-4 M3(App):`POST /api/v1.0/search/ask-stream/` 的 SSE 客户端。
 *
 * 走宿主鉴权 OkHttp(AuthInterceptor Bearer + 401 刷新,遵守前端请求认证
 * 约定);逐行读 `data: {...}`,映射为 [AskEvent]。collector 取消时中断
 * 底层 call(等价 Web 端「关面板 abort」——SSE 占用服务端 sync worker)。
 */
fun globalAskStream(
    okHttp: OkHttpClient,
    baseUrl: String,
    question: String,
): Flow<AskEvent> = callbackFlow {
    val body = JSONObject().put("question", question).toString()
        .toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
        .url(baseUrl.trimEnd('/') + "/api/v1.0/search/ask-stream/")
        .post(body)
        .header("Accept", "text/event-stream")
        .build()
    // 流式:总超时 0(读超时靠 call 层),collector 取消即 cancel。
    val client = okHttp.newBuilder()
        .readTimeout(java.time.Duration.ofSeconds(120))
        .callTimeout(java.time.Duration.ZERO)
        .build()
    val call = client.newCall(request)

    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            trySend(AskEvent.Failure(e.message ?: "network error"))
            close()
        }

        override fun onResponse(call: Call, response: Response) {
            response.use { resp ->
                if (!resp.isSuccessful) {
                    trySend(AskEvent.Failure("HTTP ${resp.code}"))
                    close()
                    return
                }
                val source = resp.body?.source() ?: run {
                    trySend(AskEvent.Failure("empty body"))
                    close()
                    return
                }
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        parseEvent(payload)?.let { trySend(it) }
                    }
                } catch (e: IOException) {
                    // collector 取消 → call cancel → 这里以 IOException 收尾,静默。
                    Log.d(TAG, "stream ended: ${e.message}")
                }
                close()
            }
        }
    })
    awaitClose { call.cancel() }
}

private fun parseEvent(payload: String): AskEvent? = runCatching {
    val obj = JSONObject(payload)
    when (obj.optString("type")) {
        "meta" -> {
            val citations = buildList {
                val arr = obj.optJSONArray("citations") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    add(
                        AskCitation(
                            n = c.optInt("n"),
                            kind = c.optString("kind", "meeting"),
                            title = c.optString("title"),
                            snippet = c.optString("snippet"),
                            cid = c.optString("cid").takeIf { it.isNotBlank() },
                            seq = if (c.has("seq") && !c.isNull("seq")) c.optLong("seq") else null,
                            roomId = c.optString("room_id").takeIf { it.isNotBlank() },
                            date = c.optString("date").takeIf { it.isNotBlank() },
                            eventId = c.optString("event_id").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
            val sources = buildMap {
                val s = obj.optJSONObject("sources") ?: return@buildMap
                s.keys().forEach { key -> put(key, s.optString(key)) }
            }
            AskEvent.Meta(citations, sources)
        }
        "delta" -> AskEvent.Delta(obj.optString("text"))
        "done" -> {
            val used = buildList {
                val arr = obj.optJSONArray("citations_used") ?: return@buildList
                for (i in 0 until arr.length()) add(arr.optInt(i))
            }
            AskEvent.Done(used, obj.optBoolean("degraded", false))
        }
        "error" -> AskEvent.Failure(obj.optString("message", "error"))
        else -> null
    }
}.getOrNull()
