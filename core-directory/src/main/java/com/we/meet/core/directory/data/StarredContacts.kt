package com.we.meet.core.directory.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 星标联系人的进程级单一真相 —— 一份 we-meet user id 集合 + 一个 [StateFlow]。
 *
 * 单例是**故意**的:通讯录/成员详情(`:app`)和会话列表(`:feature-im`)都要读同一
 * 份状态。各自建实例的话,在详情页打了星标,会话列表那颗 ⭐ 要等自己的缓存过期
 * 才跟上 —— 用户看到的是两种说法。共享一个 flow 后,写一次两处同时重组。
 *
 * 只缓存 id:名字/头像/部门这些卡片信息随处都能从目录拿到,存在这里只会变陈旧。
 * 需要完整卡片的页面(星标列表页)自己调 [DirectoryRepository.listStarred]。
 */
object StarredContacts {

    private val _ids = MutableStateFlow<Set<String>>(emptySet())

    /** Starred we-meet user ids. Empty until the first successful [refresh]. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    @Volatile
    private var repository: DirectoryRepository? = null

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Wire up once from the host app (WeMeetApp.onCreate) and prime the set. */
    fun init(repository: DirectoryRepository) {
        this.repository = repository
        refresh()
    }

    /** Re-pull from the server. Fire-and-forget: a failure leaves the last set. */
    fun refresh() {
        val repo = repository ?: return
        scope.launch {
            repo.listStarred().onSuccess { members ->
                _ids.value = members.map { it.id }.toSet()
            }
        }
    }

    /**
     * 打/取消星标。先乐观改本地集合(开关立刻响应、⭐ 立刻出现),再落库;失败就
     * 回滚到写之前的集合 —— 不做回滚的话 UI 会一直显示一个服务端并不存在的星标。
     *
     * [onError] 在写失败时回调(主线程无保证,调用方自行切)。
     */
    fun setStarred(userId: String, starred: Boolean, onError: (Throwable) -> Unit = {}) {
        val repo = repository ?: return
        val previous = _ids.value
        _ids.value = if (starred) previous + userId else previous - userId
        scope.launch {
            repo.setStarred(userId, starred).onFailure { e ->
                _ids.value = previous
                onError(e)
            }
        }
    }

    fun isStarred(userId: String): Boolean = userId in _ids.value

    /**
     * 用刚从服务端取回的成员卡片(`MemberDto.isStarred`)校准本地集合。
     *
     * 集合只在 app 启动和进星标页时全量刷新,所以在别的端(Web)打的星标可能还没
     * 传过来;成员详情那次请求的 `is_starred` 是最新的,拿它纠正一条更便宜也更准。
     * 纯读路径 —— 不回写服务端。
     */
    fun reconcile(userId: String, starred: Boolean) {
        val current = _ids.value
        if (starred == userId in current) return
        _ids.value = if (starred) current + userId else current - userId
    }

    /**
     * Forget the current account's stars on sign-out so the next account doesn't
     * inherit them. The repository stays wired (it's built once per process and
     * its OkHttp carries whatever token is current), so a re-login in the same
     * process just needs a [refresh].
     */
    fun clear() {
        _ids.value = emptySet()
    }
}
