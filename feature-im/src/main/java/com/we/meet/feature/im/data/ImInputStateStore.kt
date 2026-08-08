package com.we.meet.feature.im.data

import android.content.Context
import org.json.JSONObject

internal data class LocalImDraft(
    val text: String,
    val reply: ImDraftReplyDto?,
    val updatedAt: Long,
)

/** Account-scoped local fallback for drafts; attachments are intentionally absent. */
internal class ImInputStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("im_input_state_v2", Context.MODE_PRIVATE)

    private fun key(userId: String, cid: String) = "draft:$userId:$cid"

    fun draft(userId: String, cid: String): LocalImDraft? {
        val raw = prefs.getString(key(userId, cid), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val replyJson = json.optJSONObject("reply")
            LocalImDraft(
                text = json.optString("text"),
                reply = replyJson?.let {
                    ImDraftReplyDto(
                        mid = it.optString("mid"),
                        sender = it.optString("sender"),
                        summary = it.optString("summary"),
                    )
                },
                updatedAt = json.optLong("updated_at"),
            )
        }.getOrElse {
            // Migrate the text-only P2 preview format without losing the draft.
            LocalImDraft(raw, null, 0L)
        }
    }

    fun putDraft(
        userId: String,
        cid: String,
        text: String,
        reply: ImDraftReplyDto? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        prefs.edit().apply {
            if (text.isEmpty() && reply == null) {
                remove(key(userId, cid))
            } else {
                val json = JSONObject()
                    .put("text", text)
                    .put("updated_at", updatedAt)
                reply?.let {
                    json.put(
                        "reply",
                        JSONObject()
                            .put("mid", it.mid)
                            .put("sender", it.sender)
                            .put("summary", it.summary),
                    )
                }
                putString(key(userId, cid), json.toString())
            }
        }.apply()
    }

    fun drafts(userId: String): Map<String, LocalImDraft> {
        val prefix = "draft:$userId:"
        return prefs.all.keys
            .asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { storedKey ->
                val cid = storedKey.removePrefix(prefix)
                draft(userId, cid)?.let { cid to it }
            }
            .toMap()
    }

    fun clearUser(userId: String) {
        val prefix = "draft:$userId:"
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
        }.apply()
    }
}
