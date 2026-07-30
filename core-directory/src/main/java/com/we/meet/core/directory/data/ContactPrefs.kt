package com.we.meet.core.directory.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 逐联系人偏好的进程级单一真相 —— 两组 we-meet user id 集合 + 两个 [StateFlow]。
 *
 * **两组独立**(对标企业微信,后端是 `ContactPreference` 的两个 flag):
 * - [starredIds] 星标:只管归类(通讯录分组 + 会话列表 ⭐),**不碰通知**;
 * - [specialAlertIds] 他的消息特别提醒:只管通知(消息穿透免打扰时段 + 列表标记)。
 *
 * 同一个人可以只在其中一组里。别把两者并成一个集合 —— 打星标只是「我想快点找到
 * 这个人」,不该顺带决定半夜手机响不响。
 *
 * 单例是**故意**的:通讯录/成员详情(`:app`)和会话列表(`:feature-im`)都要读同一
 * 份状态。各自建实例的话,在详情页打了星标,会话列表那颗 ⭐ 要等自己的缓存过期
 * 才跟上 —— 用户看到的是两种说法。共享一个 flow 后,写一次两处同时重组。
 *
 * 只缓存 id:名字/头像/部门这些卡片信息随处都能从目录拿到,存在这里只会变陈旧。
 * 需要完整卡片的页面(星标列表页)自己调 [DirectoryRepository.listStarred]。
 */
object ContactPrefs {

    private val _starredIds = MutableStateFlow<Set<String>>(emptySet())
    private val _specialAlertIds = MutableStateFlow<Set<String>>(emptySet())

    /** 星标联系人 id。首次 [refresh] 成功前为空。 */
    val starredIds: StateFlow<Set<String>> = _starredIds.asStateFlow()

    /** 开了「他的消息特别提醒」的联系人 id。首次 [refresh] 成功前为空。 */
    val specialAlertIds: StateFlow<Set<String>> = _specialAlertIds.asStateFlow()

    @Volatile
    private var repository: DirectoryRepository? = null

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Wire up once from the host app (WeMeetApp.onCreate) and prime both sets. */
    fun init(repository: DirectoryRepository) {
        this.repository = repository
        refresh()
    }

    /**
     * Re-pull both sets from the server. Fire-and-forget: a failure leaves the
     * last known sets alone.
     *
     * 走 `contact-prefs/`(flag 紧凑清单)而不是星标卡片列表 —— 会话列表要给「从没
     * 拉过卡片」的对端打标记,只需要 id + 布尔。
     */
    fun refresh() {
        val repo = repository ?: return
        scope.launch {
            repo.listContactPrefs().onSuccess { rows ->
                _starredIds.value = rows.filter { it.isStarred }.map { it.userId }.toSet()
                _specialAlertIds.value =
                    rows.filter { it.specialAlert }.map { it.userId }.toSet()
            }
        }
    }

    /**
     * 打/取消星标。先乐观改本地集合(开关立刻响应、⭐ 立刻出现),再落库;失败就
     * 回滚 —— 不回滚的话 UI 会一直显示一个服务端并不存在的星标。
     *
     * 只发 `is_starred`,所以服务端不会顺手动「特别提醒」。
     */
    fun setStarred(userId: String, starred: Boolean, onError: (Throwable) -> Unit = {}) =
        update(_starredIds, userId, starred, onError) { id, value ->
            repository?.setContactPref(id, isStarred = value)
        }

    /**
     * 开/关「他的消息特别提醒」。同样乐观更新 + 失败回滚,且只发
     * `special_alert`,不影响星标。
     */
    fun setSpecialAlert(
        userId: String,
        enabled: Boolean,
        onError: (Throwable) -> Unit = {},
    ) = update(_specialAlertIds, userId, enabled, onError) { id, value ->
        repository?.setContactPref(id, specialAlert = value)
    }

    /** 乐观改集合 → 落库 → 失败回滚。两个 flag 唯一的差别是发哪个字段。 */
    private fun update(
        target: MutableStateFlow<Set<String>>,
        userId: String,
        value: Boolean,
        onError: (Throwable) -> Unit,
        write: suspend (String, Boolean) -> Result<MemberDto>?,
    ) {
        if (repository == null) return
        val previous = target.value
        target.value = if (value) previous + userId else previous - userId
        scope.launch {
            write(userId, value)?.onFailure { e ->
                target.value = previous
                onError(e)
            }
        }
    }

    fun isStarred(userId: String): Boolean = userId in _starredIds.value

    fun hasSpecialAlert(userId: String): Boolean = userId in _specialAlertIds.value

    /**
     * 用刚从服务端取回的成员卡片(`MemberDto`)校准本地两个集合。
     *
     * 集合只在 app 启动和进星标页时全量刷新,所以在别的端(Web)改的开关可能还没
     * 传过来;成员详情那次请求带的两个 flag 是最新的,拿它纠正一条更便宜也更准。
     * 纯读路径 —— 不回写服务端。
     */
    fun reconcile(userId: String, starred: Boolean, specialAlert: Boolean) {
        reconcileOne(_starredIds, userId, starred)
        reconcileOne(_specialAlertIds, userId, specialAlert)
    }

    private fun reconcileOne(
        target: MutableStateFlow<Set<String>>,
        userId: String,
        value: Boolean,
    ) {
        val current = target.value
        if (value == userId in current) return
        target.value = if (value) current + userId else current - userId
    }

    /**
     * Forget the current account's flags on sign-out so the next account doesn't
     * inherit them. The repository stays wired (it's built once per process and
     * its OkHttp carries whatever token is current), so a re-login in the same
     * process just needs a [refresh].
     */
    fun clear() {
        _starredIds.value = emptySet()
        _specialAlertIds.value = emptySet()
    }
}
