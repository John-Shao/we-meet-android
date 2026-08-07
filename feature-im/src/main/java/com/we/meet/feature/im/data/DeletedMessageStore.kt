package com.we.meet.feature.im.data

import android.content.Context

/**
 * 「删除消息」= 仅对我删除(微信/飞书语义,不影响其他成员)。
 *
 * P24 起服务端(jusi `message_deletions`)才是真相,拉历史时已删的行根本不会
 * 回来。这份本地缓存降级为两件事:①服务端回声到达前的乐观隐藏;②P24 之前的
 * 存量——进会话时补发给服务端后 [clear] 掉(见 ChatViewModel.migrateLocalDeletions)。
 */
internal class DeletedMessageStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("im_deleted_messages", Context.MODE_PRIVATE)

    /** Persisted deleted mids for [cid] (empty when none). */
    fun get(cid: String): Set<Long> =
        prefs.getStringSet(key(cid), emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    /** Merge [mids] into [cid]'s deleted set. No-op for an empty collection. */
    fun add(cid: String, mids: Collection<Long>) {
        if (mids.isEmpty()) return
        // getStringSet's result must not be mutated — build a fresh set.
        val merged = (get(cid) + mids).map { it.toString() }.toSet()
        prefs.edit().putStringSet(key(cid), merged).apply()
    }

    /** Forget [cid]'s local set — called once the server has taken it over. */
    fun clear(cid: String) {
        prefs.edit().remove(key(cid)).apply()
    }

    private fun key(cid: String) = "cid:$cid"
}
