package com.we.meet.feature.im.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed message content — the single extension point for new content types.
 * Wire conventions mirror the web client (ChatPane.tsx / MessageItem.tsx):
 * voice `{key,duration}` (ms), quote `{reply_to:{sender,snippet},text}`,
 * recall `{target_mid}`, reaction `{target_mid,emoji,op}`,
 * merged `{title,count,items:[{sender,text,ts}]}`.
 */
sealed interface MessageContent {
    data class Text(val body: String) : MessageContent

    /** body = OSS object_key; resolved to a presigned URL at render time. */
    data class Image(val objectKey: String) : MessageContent

    /** body = JSON `{key,name,size}`. */
    data class File(val key: String, val name: String, val size: Long) : MessageContent

    /** body = JSON `{key,duration}`; duration in milliseconds. */
    data class Voice(val key: String, val durationMs: Long) : MessageContent

    /** Reply message: baked-in quoted sender/snippet + the reply text. */
    data class Quote(val quotedSender: String, val quotedSnippet: String, val text: String) : MessageContent

    /** Merged-forward chat record; sender names and text are baked in at forward time. */
    data class Merged(val title: String, val count: Int, val items: List<MergedItem>) : MessageContent

    /** Control message: tombstone for [targetMid]. Not rendered as a row itself. */
    data class Recall(val targetMid: Long) : MessageContent

    /** Control message: emoji reaction on [targetMid]; op = add | remove. */
    data class Reaction(val targetMid: Long, val emoji: String, val op: String) : MessageContent

    /** Server-injected notices (member joined/left, meeting summary, ...). */
    data class System(val body: String) : MessageContent

    /**
     * P1 一对一通话 log — body = JSON `{media,result,duration?}`. Sent by the
     * caller on every terminal: non-connected (canceled/missed/declined/busy/
     * unreachable) AND completed calls, where [durationSec] carries the 通话
     * 时长. media = audio | video. Rendering is perspective-aware: the sender
     * is always the CALLER, so isOwn ⇒ caller wording, !isOwn ⇒ callee wording.
     */
    data class CallLog(
        val media: String,
        val result: String,
        val durationSec: Long = 0,
        /** P4.1: group end-records carry the room slug to flip the matching
         * ongoing card to its ended state. */
        val slug: String? = null,
    ) : MessageContent

    /**
     * P4.1 群语音「进行中」卡片 — body = JSON `{slug,media}`. Sent by the
     * initiator when the group call starts; every member can tap to join the
     * live voice grid. Rendered as ended once a CallLog with the same slug
     * exists downstream (or the join-time room resolve fails).
     */
    data class GroupCall(val slug: String, val media: String) : MessageContent

    /**
     * P3 手机号被查看提示 — content_type `phone-viewed`, sent server-side when
     * someone reveals the owner's full number. Sender is always the VIEWER, so
     * rendering is perspective-aware: !isOwn (owner receiving) ⇒「对方查看了你的
     * 手机号码」; isOwn (viewer) ⇒「你查看了对方的手机号码」. Body carries nothing.
     */
    data object PhoneViewed : MessageContent

    /** Anything this client version doesn't render natively yet. */
    data class Unsupported(val contentType: String, val body: String) : MessageContent
}

/** One line of a merged chat record; `ts` is unix millis. */
data class MergedItem(val sender: String, val text: String, val ts: Long)

object MessageContentParser {

    fun parse(contentType: String, body: String): MessageContent = when (contentType) {
        "text", "" -> MessageContent.Text(body)
        "image" -> MessageContent.Image(body)
        "file" -> parseFile(body)
        "voice" -> parseJson(contentType, body) {
            MessageContent.Voice(key = it.getString("key"), durationMs = it.optLong("duration", 0L))
        }
        "quote" -> parseJson(contentType, body) {
            val replyTo = it.optJSONObject("reply_to")
            MessageContent.Quote(
                quotedSender = replyTo?.optString("sender").orEmpty(),
                quotedSnippet = replyTo?.optString("snippet").orEmpty(),
                text = it.optString("text"),
            )
        }
        "merged" -> parseJson(contentType, body) { json ->
            val items = json.optJSONArray("items") ?: JSONArray()
            MessageContent.Merged(
                title = json.optString("title"),
                count = json.optInt("count", items.length()),
                items = (0 until items.length()).mapNotNull { i ->
                    items.optJSONObject(i)?.let { o ->
                        MergedItem(
                            sender = o.optString("sender"),
                            text = o.optString("text"),
                            ts = o.optLong("ts", 0L),
                        )
                    }
                },
            )
        }
        "recall" -> parseJson(contentType, body) {
            MessageContent.Recall(targetMid = it.getLong("target_mid"))
        }
        "reaction" -> parseJson(contentType, body) {
            MessageContent.Reaction(
                targetMid = it.getLong("target_mid"),
                emoji = it.getString("emoji"),
                op = it.optString("op", "add"),
            )
        }
        "system" -> MessageContent.System(body)
        "phone-viewed" -> MessageContent.PhoneViewed
        "call-log" -> parseJson(contentType, body) {
            MessageContent.CallLog(
                media = it.optString("media", "audio"),
                result = it.optString("result", "missed"),
                durationSec = it.optLong("duration", 0L),
                slug = it.optString("slug").takeIf { s -> s.isNotBlank() },
            )
        }
        "group-call" -> parseJson(contentType, body) {
            MessageContent.GroupCall(
                slug = it.getString("slug"),
                media = it.optString("media", "audio"),
            )
        }
        else -> MessageContent.Unsupported(contentType, body)
    }

    /** True for message types that must not render as their own chat row. */
    fun isControlType(contentType: String): Boolean =
        contentType == "recall" || contentType == "reaction"

    private fun parseFile(body: String): MessageContent = parseJson("file", body) {
        MessageContent.File(
            key = it.getString("key"),
            name = it.optString("name", "file"),
            size = it.optLong("size", -1L),
        )
    }

    private inline fun parseJson(
        contentType: String,
        body: String,
        block: (JSONObject) -> MessageContent,
    ): MessageContent = try {
        block(JSONObject(body))
    } catch (_: Throwable) {
        MessageContent.Unsupported(contentType, body)
    }
}

/** 通话时长 mm:ss (h:mm:ss beyond an hour) for completed call-log rows. */
fun formatCallDuration(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}

/** Human-readable byte count for file bubbles (matches web's rendering scale). */
fun formatFileSize(size: Long): String = when {
    size < 0 -> ""
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    size < 1024 * 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
    else -> "%.1f GB".format(size / (1024.0 * 1024.0 * 1024.0))
}
