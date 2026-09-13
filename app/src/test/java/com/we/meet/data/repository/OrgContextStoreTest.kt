package com.we.meet.data.repository

import com.we.meet.core.directory.DirectoryDeps
import com.we.meet.core.directory.data.DirectoryRepository
import com.we.meet.core.directory.data.OrgRefDto
import com.we.meet.core.directory.net.DirectoryNetwork
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「当前组织」那一行的三条静默失效路径 —— 每一条都只在界面上表现为"看起来怪",
 * 不会抛异常、不会报错:
 *
 * - 服务端回 `organization: null` 时不落到 [OrgState.None],那一行会一直空着/占位;
 * - 失败时把已有的值丢掉,用户正看着的组织名会突然消失(网络抖一下而已);
 * - 失败时留在 [OrgState.Unknown],那条占位灰条就永远不走了。
 *
 * 另外两条:成功要把值写进缓存(下次冷启动第一帧就有),失败**不能**动缓存
 * —— 缓存里是最后一次权威值,清掉就等于把一个还没被推翻的答案扔了。
 *
 * 用 OkHttp 的 Interceptor 就地截请求、回假响应,与 `DirectoryRequestContractTest`
 * 同一套做法(仓库里没有 MockWebServer);缓存用两个局部变量扮演 TokenStore。
 */
class OrgContextStoreTest {

    private val org = OrgRefDto(id = "o1", name = "Default Organization")

    private var responseCode = 200
    private var responseBody = "{}"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request: Request = chain.request()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private val repository = DirectoryRepository(
        DirectoryNetwork.directoryApi(
            object : DirectoryDeps {
                override val authedOkHttp: OkHttpClient get() = client
                override val baseUrl: String = "https://example.test/"
            },
        ),
    )

    /** 扮演 TokenStore 的两个键。 */
    private var cached: OrgRefDto? = null

    /** 每次新建,好让"启动时读到本地缓存"这一档也能测。 */
    private fun store() = OrgContextStore(
        repository = repository,
        readCached = { cached },
        writeCached = { cached = it },
    )

    private fun respond(code: Int, body: String) {
        responseCode = code
        responseBody = body
    }

    // ── 首次加载 ────────────────────────────────────────────────────────────

    @Test
    fun noCacheYetMeansUnknownSoTheRowCanReserveItsSpace() {
        assertEquals(OrgState.Unknown, store().state.value)
    }

    @Test
    fun aCachedOrganizationIsAvailableOnTheVeryFirstFrame() {
        cached = org

        // 第一帧就有值 —— 那一行不必等网络,也就不会"先空后跳"。
        assertEquals(OrgState.Known(org), store().state.value)
    }

    @Test
    fun aHalfWrittenCacheIsNotAnOrganization() {
        // 只有 id、没有名字:画一个空名字比占位更糟,所以按"还不知道"处理。
        cached = OrgRefDto(id = "o1", name = "  ")

        assertEquals(OrgState.Unknown, store().state.value)
    }

    // ── 刷新:成功 ──────────────────────────────────────────────────────────

    @Test
    fun successShowsTheOrganizationAndWritesItThroughToTheCache() = runBlocking {
        respond(200, """{"organization":{"id":"o1","name":"Default Organization"}}""")
        val store = store()

        store.refresh()

        assertEquals(OrgState.Known(org), store.state.value)
        // 冷启动/切页面时读的就是它。
        assertEquals(org, cached)
    }

    @Test
    fun noMembershipMeansNoRowAndClearsTheStaleCache() = runBlocking {
        cached = org
        respond(200, """{"organization":null,"org_role":null,"is_org_admin":false}""")
        val store = store()

        store.refresh()

        // 被移出组织后,本地那份不能继续当"当前组织"用。
        assertEquals(OrgState.None, store.state.value)
        assertNull(cached)
    }

    @Test
    fun aBlankNameIsNotAnOrganization() = runBlocking {
        respond(200, """{"organization":{"id":"o1","name":"   "}}""")
        val store = store()

        store.refresh()

        assertEquals(OrgState.None, store.state.value)
        assertNull(cached)
    }

    // ── 刷新:失败 ──────────────────────────────────────────────────────────

    @Test
    fun aFailedRefreshKeepsWhatTheUserIsAlreadyLookingAt() = runBlocking {
        cached = org
        respond(500, """{"detail":"boom"}""")
        val store = store()

        store.refresh()

        assertEquals(OrgState.Known(org), store.state.value)
        // 缓存是最后一次权威值:一次失败不该把它清掉(否则下次冷启动又得等网络)。
        assertEquals(org, cached)
    }

    @Test
    fun aFailedFirstLoadStopsThePlaceholderInsteadOfLeavingItForever() = runBlocking {
        respond(500, """{"detail":"boom"}""")
        val store = store()

        store.refresh()

        // 与这次改动之前的行为一致:拿不到就不显示那一行。留在 Unknown 的话,
        // 那条占位灰条会一直挂着,比不显示更糟。
        assertEquals(OrgState.None, store.state.value)
    }

    // ── 登出 ────────────────────────────────────────────────────────────────

    @Test
    fun clearDropsTheStateSoTheNextAccountDoesNotInheritIt() = runBlocking {
        respond(200, """{"organization":{"id":"o1","name":"Default Organization"}}""")
        val store = store()
        store.refresh()

        store.clear()

        assertEquals(OrgState.Unknown, store.state.value)
    }
}
