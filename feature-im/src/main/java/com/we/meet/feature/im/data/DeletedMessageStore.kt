package com.we.meet.feature.im.data

import android.content.Context

/**
 * 「删除消息」= 仅本端删除(微信/飞书语义)。jusi-light-im 暂无服务端删除端点,
 * 后端只验成员身份;真正的隐藏在客户端。已删 mid 按 cid 持久化到本设备,
 * 使删除在刷新/重进会话/重启后不复现(但不影响其他成员,他们仍能看到)。
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

    private fun key(cid: String) = "cid:$cid"
}
