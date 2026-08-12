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
        /** P4-M3: true = 未接通的会议邀请记录(body kind:"meet"),渲染为
         * 「会议邀请 · x」而非未接来电。 */
        val meetInvite: Boolean = false,
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

    /**
     * P8 日程卡片 — content_type `event-card`(与 Web/后端统一 kebab-case),
     * body = 协议 v1 JSON `{v,kind,event_id,title,start,end,all_day,
     * attendee_count,organizer_name,recurrence_scope?,...}`。start/end 为
     * ISO-8601 UTC,渲染时转本地时区;kind 未知按 created 渲染;创建卡由客户端
     * 发,变更/取消卡由后端发。
     */
    data class EventCard(
        val eventId: String,
        val title: String,
        val startIso: String,
        val endIso: String,
        val allDay: Boolean = false,
        val attendeeCount: Int = 0,
        val organizerName: String = "",
        /** private cards sent to a source conversation intentionally omit event details. */
        val visibility: String = "default",
        /** created | invited | time_changed | attendees_changed | removed | rsvp_changed | cancelled */
        val kind: String = "created",
        /**
         * kind=time_changed 时的**改期前**时间窗。后端一直在发这两个键,App 端
         * 原本连解析都没有 —— Web 会把旧窗划掉显示「原 8/10 10:00–11:00」,App
         * 只有一个「时间已变更」徽章,看不出改成什么样了。金标准 fixture 契约测
         * 试抓到的第二处漂移(第一处是 Web 漏拷 removed_count)。
         */
        val oldStartIso: String = "",
        val oldEndIso: String = "",
        /** one | following | all; blank for non-series cards and unknown values. */
        val recurrenceScope: String = "",
        /** kind=attendees_changed 时的增减人数(缺省 0 = 后端没发这一项)。 */
        val addedCount: Int = 0,
        val removedCount: Int = 0,
        /** kind=rsvp_changed 时的回复人和状态。 */
        val responderName: String = "",
        val rsvpStatus: String = "",
    ) : MessageContent

    /**
     * 分享云文档到聊天 — content_type `doc-card`,body = 协议 v1 JSON
     * `{v,doc_id,title,url,shared_by?}`。与 EventCard 不同,这是分享时刻的
     * 静态快照,不追更——没有 kind 多态。
     */
    data class DocCard(
        val docId: String,
        val title: String,
        val url: String,
    ) : MessageContent

    /**
     * 分享会议到聊天 — content_type `meeting-card`,body = 协议 v1 JSON
     * `{v,room_id?,slug,title,status,scheduled_at?}`(与 Web meetingCard.ts 统一;
     * room_id 由 Web 侧携带,App 分享只有 slug 故可缺省)。静态快照,点卡片按
     * slug 走入会预览。status = scheduled | ongoing;scheduled_at 为 ISO-8601 UTC。
     */
    data class MeetingCard(
        val slug: String,
        val title: String,
        val status: String = "ongoing",
        val scheduledAtIso: String = "",
        val roomId: String = "",
    ) : MessageContent

    /**
     * 群机器人经 webhook 发来的富文本 — content_type `rich-text`。协议与解析
     * 都在 [RichText.kt];这里只是 sealed 分支,让 MessageBubble 的 when 穷尽。
     */
    data class RichText(val body: RichTextBody) : MessageContent

    /**
     * 群机器人经 webhook 发来的块级卡片 — content_type `rich-card`。协议与解析
     * 都在 [RichCard.kt];这里只是 sealed 分支,让 MessageBubble 的 when 穷尽。
     */
    data class RichCard(val body: RichCardBody) : MessageContent

    /**
     * 卡片按钮的点击结果 — content_type `card-state`(A2)。**控制消息**:
     * 不自成一行,由 ChatViewModel 回放成叠加层挂在 [CardStateBody.targetMid]
     * 那张卡上。协议与解析在 [CardState.kt]。
     */
    data class CardState(val body: CardStateBody) : MessageContent

    /** Anything this client version doesn't render natively yet. */
    data class Unsupported(val contentType: String, val body: String) : MessageContent
}

/** One line of a merged chat record; `ts` is unix millis. */
data class MergedItem(val sender: String, val text: String, val ts: Long)

/** jusi-light-im 服务端注入消息的 SYSTEM 身份 uid(与 Web IM_SYSTEM_UID 一致)。 */
const val IM_SYSTEM_UID = "00000000-0000-0000-0000-000000000000"

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
                meetInvite = it.optString("kind") == "meet",
            )
        }
        "group-call" -> parseJson(contentType, body) {
            MessageContent.GroupCall(
                slug = it.getString("slug"),
                media = it.optString("media", "audio"),
            )
        }
        "event-card" -> parseJson(contentType, body) {
            MessageContent.EventCard(
                eventId = it.optString("event_id"),
                // title 缺失视为坏卡 → getString 抛异常落 Unsupported 兜底。
                title = it.getString("title"),
                startIso = it.optString("start"),
                endIso = it.optString("end"),
                allDay = it.optBoolean("all_day", false),
                attendeeCount = it.optInt("attendee_count", 0),
                organizerName = it.optString("organizer_name"),
                visibility = it.optString("visibility", "default")
                    .takeIf { visibility -> visibility == "private" }
                    ?: "default",
                kind = it.optString("kind", "created"),
                oldStartIso = it.optString("old_start"),
                oldEndIso = it.optString("old_end"),
                recurrenceScope = it.optString("recurrence_scope")
                    .takeIf { scope -> scope in setOf("one", "following", "all") }
                    .orEmpty(),
                addedCount = it.optInt("added_count", 0),
                removedCount = it.optInt("removed_count", 0),
                responderName = it.optString("responder_name"),
                rsvpStatus = it.optString("rsvp_status")
                    .takeIf { status ->
                        status in setOf("needs_action", "accepted", "declined", "tentative")
                    }
                    .orEmpty(),
            )
        }
        "doc-card" -> parseJson(contentType, body) {
            MessageContent.DocCard(
                docId = it.optString("doc_id"),
                // title 缺失视为坏卡 → getString 抛异常落 Unsupported 兜底。
                title = it.getString("title"),
                url = it.optString("url"),
            )
        }
        "rich-text" -> RichTextParser.parse(body)
            ?.let { MessageContent.RichText(it) }
            ?: MessageContent.Unsupported(contentType, body)
        "rich-card" -> RichCardParser.parse(body)
            ?.let { MessageContent.RichCard(it) }
            ?: MessageContent.Unsupported(contentType, body)
        "card-state" -> CardStateParser.parse(body)
            ?.let { MessageContent.CardState(it) }
            ?: MessageContent.Unsupported(contentType, body)
        "meeting-card" -> parseJson(contentType, body) {
            MessageContent.MeetingCard(
                // slug 是入会关键,缺失=坏卡 → getString 抛异常落 Unsupported 兜底。
                slug = it.getString("slug"),
                title = it.optString("title"),
                status = if (it.optString("status") == "scheduled") "scheduled" else "ongoing",
                scheduledAtIso = it.optString("scheduled_at"),
                roomId = it.optString("room_id"),
            )
        }
        else -> MessageContent.Unsupported(contentType, body)
    }

    /** True for message types that must not render as their own chat row. */
    fun isControlType(contentType: String): Boolean =
        contentType == "recall" || contentType == "reaction" ||
            // card-state = 卡片按钮的点击结果(叠加层)。只该改已有卡片的渲染,
            // 不该自成一行。本地这份是兜底:jusi 的 IM_NONBUMPING_CONTENT_TYPES
            // 漏配时降级成「会话冒泡」,而不是列表里出现一坨 JSON。
            contentType == "card-state"

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
