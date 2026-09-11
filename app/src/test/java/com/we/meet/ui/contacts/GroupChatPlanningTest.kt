package com.we.meet.ui.contacts

import com.we.meet.core.directory.data.DepartmentDto
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.data.MemberPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「发起群聊」的两件纯逻辑:翻页拿成员([planGroupChat])与按钮可不可点
 * ([canStartGroupChat])。
 *
 * 为什么值得单测:这是 App 里唯一能一次建 300 人群的路径,而它的三种失败方式**都是静默
 * 的** —— 少翻一页只是群里少几个人(没人会去数)、把自己也传进去只是确认框上的人数和
 * 群里差一个、上限判断写反了只是「点了没反应」或「建出一个几百人的群」。这些都不会让
 * 界面报错,所以只能靠测试钉住。
 */
class GroupChatPlanningTest {

    private fun member(id: String, self: Boolean = false) = MemberDto(
        id = id,
        fullName = "成员$id",
        isSelf = self,
    )

    private fun page(
        members: List<MemberDto>,
        total: Int = members.size,
        hasMore: Boolean = false,
        nextPage: Int = 2,
    ) = MemberPage(members = members, hasMore = hasMore, nextPage = nextPage, total = total)

    // ── 翻页 ────────────────────────────────────────────────────────────────

    @Test
    fun pagesThroughTheWholeDepartment() = runBlocking {
        // 三页:100 + 100 + 20。只取第一页是这类实现最经典的错(群里少 120 个人)。
        val calls = mutableListOf<Int>()
        val result = planGroupChat(fetchPage = { page, pageSize ->
            calls += page
            assertEquals(GROUP_CHAT_PAGE_SIZE, pageSize)
            when (page) {
                1 -> Result.success(
                    page(
                        (1..100).map { member("u$it") },
                        total = 220,
                        hasMore = true,
                        nextPage = 2,
                    ),
                )

                2 -> Result.success(
                    page(
                        (101..200).map { member("u$it") },
                        total = 220,
                        hasMore = true,
                        nextPage = 3,
                    ),
                )

                else -> Result.success(page((201..220).map { member("u$it") }, total = 220))
            }
        })

        val plan = result.getOrThrow()
        assertTrue(plan is GroupChatPlan.Ready)
        assertEquals(220, (plan as GroupChatPlan.Ready).memberIds.size)
        assertEquals(listOf(1, 2, 3), calls)
    }

    @Test
    fun neverIncludesSelf() = runBlocking {
        // 自己由服务端作为群主加进去:传进去只会在服务端被丢掉,却让确认框上的人数多一个。
        val plan = planGroupChat(fetchPage = { _, _ ->
            Result.success(
                page(listOf(member("me", self = true), member("u1"), member("u2"))),
            )
        }).getOrThrow()

        assertEquals(listOf("u1", "u2"), (plan as GroupChatPlan.Ready).memberIds)
    }

    @Test
    fun refusesAboveTheCapWithoutPagingAnyFurther() = runBlocking {
        // 超过上限就明说拉不了 —— 关键在「不再往下翻」:那几百个人的名字没必要拉回来。
        val calls = mutableListOf<Int>()
        val plan = planGroupChat(fetchPage = { page, _ ->
            calls += page
            Result.success(
                page((1..100).map { member("u$it") }, total = 400, hasMore = true),
            )
        }).getOrThrow()

        assertEquals(GroupChatPlan.TooMany(400), plan)
        assertEquals(listOf(1), calls)
    }

    @Test
    fun stopsIfTheReturnedMembersDisagreeWithTheReportedCount() = runBlocking {
        // count 说 100,实际每页都返回 100 且永远 hasMore —— 不能真的让成员涨过上限。
        val plan = planGroupChat(fetchPage = { _, _ ->
            Result.success(page((1..200).map { member("u$it") }, total = 100, hasMore = true))
        }).getOrThrow()

        assertTrue(plan is GroupChatPlan.TooMany)
        assertTrue((plan as GroupChatPlan.TooMany).count > GROUP_CHAT_MEMBER_CAP)
    }

    @Test
    fun failsInsteadOfPagingForeverWhenTheServerNeverSaysStop() = runBlocking {
        // 服务端一直回 next:必须有页码上限,否则这里会一直发请求下去。
        val calls = mutableListOf<Int>()
        val result = planGroupChat(fetchPage = { page, _ ->
            calls += page
            Result.success(page(listOf(member("u1")), total = 1, hasMore = true))
        })

        assertTrue(result.isFailure)
        // cap/pageSize + 2 = 5 页封顶,不再多。
        assertTrue("calls=$calls", calls.size <= 5)
    }

    @Test
    fun reportsAnEmptyDepartment() = runBlocking {
        // 部门里只有自己 = 一个人都拉不了,提示的是「没有成员」而不是建一个只有我的群。
        val plan = planGroupChat(fetchPage = { _, _ ->
            Result.success(page(listOf(member("me", self = true)), total = 1))
        }).getOrThrow()

        assertEquals(GroupChatPlan.Empty, plan)
    }

    @Test
    fun propagatesAFailedPage() = runBlocking {
        val boom = IllegalStateException("network")
        val second = planGroupChat(fetchPage = { page, _ ->
            if (page == 1) {
                Result.success(page(listOf(member("u1")), total = 150, hasMore = true))
            } else {
                Result.failure(boom)
            }
        })

        assertEquals(boom, second.exceptionOrNull())
    }

    // ── 按钮该不该显示 ──────────────────────────────────────────────────────

    private fun dept(memberCount: Int) = DepartmentDto(id = "d1", name = "产品部", memberCount = memberCount)

    @Test
    fun groupChatIsOfferedWhenThereIsAnyoneToPullIn() {
        assertTrue(canStartGroupChat(dept(memberCount = 5), emptyList()))
        // 直属 0 人但列表里有人(服务端口径不完全一致时):仍然该给按钮。
        assertTrue(canStartGroupChat(dept(memberCount = 0), listOf(member("u1"))))
    }

    @Test
    fun groupChatIsNotOfferedForAnEmptyDepartment() {
        // 点了必然只听到一句「没有成员」,不如不给(与 Web 一致)。
        assertFalse(canStartGroupChat(dept(memberCount = 0), emptyList()))
    }
}
