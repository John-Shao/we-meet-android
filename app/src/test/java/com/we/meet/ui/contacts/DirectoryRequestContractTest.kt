package com.we.meet.ui.contacts

import com.we.meet.core.directory.DirectoryDeps
import com.we.meet.core.directory.data.DirectoryRepository
import com.we.meet.core.directory.data.DirectoryApi
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App ↔ 后端的**查询契约**:通讯录列表发了哪些参数、卡片上的字段名对不对得上。
 *
 * 为什么值得单测:这些参数(`ordering` / `include_subtree` / `department` / `page_size`)
 * 决定了名册的顺序与范围,而它们的失效是**静默**的 —— 少发一个 `ordering=pinyin`,
 * 名册退回编码序(汉字看着像乱序);少发 `include_subtree`,列表里的人与部门行的人数
 * 就不是同一批。界面不报错,只有用户觉得"哪里不对"。
 *
 * 字段名同理:Moshi 对不上的字段是**静默丢弃**(不抛异常),而通讯录首页那一行组织名
 * 正好是「服务端没下发这个字段」与「字段名写错了」的同一副面孔 —— 都是不显示。
 *
 * 用 OkHttp 的 Interceptor 就地截请求、回假响应,不引入 MockWebServer(仓库里没有
 * 这个依赖,而为了一个契约测试加一个网络库不划算)。
 */
class DirectoryRequestContractTest {

    private val requests = mutableListOf<Request>()
    private var responseBody = "{}"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private val deps = object : DirectoryDeps {
        override val authedOkHttp: OkHttpClient get() = client
        override val baseUrl: String = "https://example.test/"
    }

    private val api: DirectoryApi = DirectoryNetwork.directoryApi(deps)
    private val repository = DirectoryRepository(api)

    private fun lastUrl(): String {
        val url = requests.last().url
        return url.encodedPath + "?" + url.encodedQuery
    }

    // ── 成员列表:排序与范围 ────────────────────────────────────────────────

    @Test
    fun memberListAlwaysAsksForPinyinOrder() = runBlocking {
        responseBody = """{"count":0,"next":null,"previous":null,"results":[]}"""

        repository.allMembers(page = 2)

        val url = lastUrl()
        assertTrue(url, url.startsWith("/api/v1.0/directory/members/"))
        assertTrue(url, url.contains("ordering=pinyin"))
        assertTrue(url, url.contains("page=2"))
    }

    @Test
    fun departmentListIncludesTheSubtree() = runBlocking {
        responseBody = """{"count":0,"next":null,"previous":null,"results":[]}"""

        repository.departmentMembers("dept-1", page = 1)

        val url = lastUrl()
        assertTrue(url, url.startsWith("/api/v1.0/directory/departments/dept-1/members/"))
        assertTrue(url, url.contains("ordering=pinyin"))
        // 与浏览同一口径(含下级部门):否则「产品部」在列表里和在搜索里是两拨人。
        assertTrue(url, url.contains("include_subtree=true"))
    }

    @Test
    fun departmentListCanAskForABiggerPage() = runBlocking {
        responseBody = """{"count":0,"next":null,"previous":null,"results":[]}"""

        // 部门级「发起群聊」要走这条:一次拉满页,三百人最多三次往返。
        repository.departmentMembers("dept-1", page = 1, pageSize = 100)

        assertTrue(lastUrl(), lastUrl().contains("page_size=100"))
    }

    // ── 字段名(Moshi 会静默丢掉对不上的字段)────────────────────────────

    @Test
    fun orgContextRequestHitsTheDirectoryMeEndpoint() = runBlocking {
        responseBody = """{"organization":{"id":"o1","name":"Acme"},"org_role":"member"}"""

        repository.orgContext()

        // 路径本身就是契约的一部分:打错一个字母是 404,而调用方把失败当作「没有组织」
        // 静默吞掉(见 ContactsTabScreen),于是通讯录首页那一行永远不显示。
        val url = lastUrl()
        assertTrue(url, url.startsWith("/api/v1.0/directory/me/"))
    }

    @Test
    fun orgContextParsesTheOrganizationRow() = runBlocking {
        responseBody = """{"organization":{"id":"o1","name":"Acme"},"org_role":"member"}"""

        val org = repository.orgContext().getOrThrow().organization

        // 首页顶部的组织名/头像就靠这两个字段;名字对不上就等于「服务端没下发」。
        assertEquals("o1", org?.id)
        assertEquals("Acme", org?.name)
    }

    @Test
    fun orgContextWithoutAMembershipIsNotAnError() = runBlocking {
        // 没有 membership 的账号(管理台建的、还没进组织)服务端回 organization: null。
        // 这时首页只是不显示那一行,不该把整页判成失败。
        responseBody = """{"organization":null,"org_role":null,"is_org_admin":false}"""

        val context = repository.orgContext().getOrThrow()

        assertNull(context.organization)
    }

    @Test
    fun memberCardFieldsMatchTheServerNames() = runBlocking {
        responseBody = """
            {"count":1,"next":null,"previous":null,"results":[
              {"id":"u1","full_name":"张三","short_name":"三","email":"z@example.com",
               "title":"工程师","initial":"Z","is_self":true,
               "department":{"id":"d1","name":"销售部"}}
            ]}
        """.trimIndent()

        val member = repository.allMembers().getOrThrow().members.single()

        assertEquals("张三", member.fullName)
        assertEquals("工程师", member.title)
        // 索引条与字母头全靠这个字段:名字对不上就等于服务端没下发。
        assertEquals("Z", member.initial)
        assertTrue(member.isSelf)
        assertEquals("销售部", member.department?.name)
    }

    @Test
    fun departmentMemberCountIsParsed() = runBlocking {
        responseBody = """[{"id":"d1","name":"销售部","member_count":7}]"""

        val depts = repository.listAllDepartments().getOrThrow()

        assertEquals(7, depts.single().memberCount)
    }
}
