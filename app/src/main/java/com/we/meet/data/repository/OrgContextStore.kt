package com.we.meet.data.repository

import com.we.meet.core.directory.data.DirectoryRepository
import com.we.meet.core.directory.data.OrgContextDto
import com.we.meet.core.directory.data.OrgRefDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * 「当前组织」的三种情形。
 *
 * 中间那一档 [Unknown] 不是可有可无的:组织那一行是插在其它内容**前面**的,若是"还没
 * 拿到就先不画",数据回来时下面整块会往下弹一下(通讯录首页是四个入口一起弹,我的页
 * 是用户名/简介一起弹)。有了这一档,调用方就能先把位置占住,数据回来只换内容。
 */
sealed interface OrgState {
    /** 还不知道(首次加载中,本地也没有缓存):那一行要占位。 */
    data object Unknown : OrgState

    /** 服务端明确回了 `organization: null`(账号还没进组织):那一行不画。 */
    data object None : OrgState

    data class Known(val org: OrgRefDto) : OrgState
}

/**
 * 当前组织的进程级单一真相 —— 通讯录首页顶部与我的页「组织」那一行共用。
 *
 * 为什么要一个共享的 store,而不是让两个页面各自 `directory/me/`:
 *
 * 1. **一处拉、两处读**。两页各拉一次就是两次请求;而且任何一处失败,用户会在两个页面
 *    看到两种说法(一边有组织、一边没有)。启动时预热一次,进页面再静默刷一次即可。
 * 2. **不能"先空后跳"**。见 [OrgState.Unknown]。
 * 3. **不必每次都等网络**。值同时落到本地(现在是 [com.we.meet.data.auth.TokenStore],
 *    与 nickname/intro 同一个来路):冷启动、切页面先用本地那份画出来,再用网络结果覆盖。
 *
 * 缓存读写做成两个 lambda 而不是直接收 TokenStore:这个类就与 Android 无关,单测里换成
 * 两个变量即可(见 `OrgContextStoreTest`)。
 *
 * @param readCached 读本地缓存;没有(或只有一半)时返回 null。
 * @param writeCached 写本地缓存;**传 null 表示清掉** —— 服务端说没有组织时,本地那份
 *   (可能来自"上一份工作")不能继续当当前组织用。
 */
class OrgContextStore(
    private val repository: DirectoryRepository,
    private val readCached: () -> OrgRefDto?,
    private val writeCached: (OrgRefDto?) -> Unit,
) {

    private val _state = MutableStateFlow(cachedOrUnknown())

    /** 通讯录首页与我的页都订阅它;有缓存时第一次组合就有值。 */
    val state: StateFlow<OrgState> = _state.asStateFlow()

    /** 同一时刻只跑一次刷新:两个页面 + 启动预热可能同时来敲。 */
    private val refreshing = Mutex()

    /**
     * 拉一次组织上下文并落到状态/缓存。调用点都在 Compose 的 `LaunchedEffect` 里
     * (本身就是协程),所以这里不需要自己持有 scope。
     *
     * 失败**保留现状**:已经显示出来的组织名继续用(下次成功再纠正),而不是把用户正在
     * 看的那一行抹掉。只有连缓存都没有时才退回 [OrgState.None] —— 否则 [OrgState.Unknown]
     * 那条占位会永远挂着,变成一条不会消失的灰条。这也是这次改动之前的行为(拿不到就
     * 不显示那一行)。
     */
    suspend fun refresh() {
        if (!refreshing.tryLock()) return
        try {
            val result = repository.orgContext()
            val next = nextOrgState(_state.value, result)
            // 只有服务端真的回了话才动缓存:失败时本地那份是"最后一次权威值",不能清。
            if (result.isSuccess) writeCached((next as? OrgState.Known)?.org)
            _state.value = next
        } finally {
            refreshing.unlock()
        }
    }

    /**
     * 退出登录:内存里这份属于上一个账号,不能留给下一个(见 `SettingsScreen` 的登出)。
     *
     * 本地那份由 `TokenStore.clear()` 一起清掉 —— 两个键都在同一份 prefs 里。
     */
    fun clear() {
        _state.value = OrgState.Unknown
    }

    private fun cachedOrUnknown(): OrgState {
        val cached = readCached() ?: return OrgState.Unknown
        // 半个缓存(名字缺了)不进 Known:那一行要显示的是名字,画一个空行比占位更糟。
        return if (cached.name.isNullOrBlank()) OrgState.Unknown else OrgState.Known(cached)
    }
}

/**
 * 一次刷新的结果 → 新状态。抽出来是因为三条分支都是"静默失败"型的:没有 `null` 的处理
 * 会把"没组织"变成"永远加载中",失败时退回 None 会把用户正在看的组织名抹掉。见单测。
 */
private fun nextOrgState(current: OrgState, result: Result<OrgContextDto>): OrgState {
    if (!result.isSuccess) {
        return if (current is OrgState.Known) current else OrgState.None
    }
    val organization = result.getOrNull()?.organization
    return if (organization != null && !organization.name.isNullOrBlank()) {
        OrgState.Known(organization)
    } else {
        OrgState.None
    }
}
