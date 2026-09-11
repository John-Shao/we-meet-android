package com.we.meet.ui.contacts

import com.we.meet.core.directory.data.DepartmentDto
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.data.MemberPage

/**
 * 部门级「发起群聊」的规模上限。
 *
 * 建群会把所有人拉进一个会话,几百人的群不该是「顺手点一下」的产物 —— 超过就明确说
 * 拉不了,而不是悄悄拉一半(那才是最坏的结果:用户以为全都在群里)。
 *
 * ⚠️ **两端的口径不完全一样,别把这个数当成「两端一定拉同一批人」**:Web 的同名按钮只
 * 拉**直属**成员(`/directory/members/?department=`,不带 `include_subtree`),而 App 的
 * 部门列表本身**含下级部门**(见 [ContactsUiState]),所以这里拉的是整棵子树 —— 与屏幕
 * 上那一批人一致。于是同一个部门可能出现「Web 建成 5 人群、App 直接说超过上限」。
 * 保持现状的理由是 App 内部自洽(屏幕上多少人、群里就多少人);差异记在
 * `docs/对齐Web端_IM通讯录日历_实施方案.md` 的 M7,要对齐只需给
 * [com.we.meet.core.directory.data.DirectoryRepository.departmentMembers] 传
 * `includeSubtree = false`(浏览与群聊要一起改,否则「数人数对不上」)。
 */
const val GROUP_CHAT_MEMBER_CAP = 300

/** 拉部门成员建群时的每页条数(服务端上限 100):300 人最多三次往返。 */
const val GROUP_CHAT_PAGE_SIZE = 100

/**
 * 「发起群聊」这一步的结论。
 *
 * 三种结果都是**正常**结论而不是异常:人拉不了(空部门 / 超上限)要说清楚是哪一种,
 * 而网络与服务端故障走 `Result.failure`。
 */
sealed interface GroupChatPlan {
    /** 能建:拉进来的成员(**不含自己** —— 建群人由服务端加)。 */
    data class Ready(val memberIds: List<String>) : GroupChatPlan

    /** 这个部门(含下级)一个人都没有。 */
    data object Empty : GroupChatPlan

    /** 超过一次拉群的人数上限。 */
    data class TooMany(val count: Int) : GroupChatPlan
}

/**
 * 翻完整个部门,决定这一步「发起群聊」能不能做。
 *
 * 三条克制(与 Web 一致):①**翻完** —— 只取第一页的话,60 人的部门建出来的群里只有
 * 50 个人,而确认框上写的也是 50,少掉的人事后几乎发现不了;②先看服务端的 count,
 * 超过上限就明说拉不了,不建半拉的群;③自己不进 `member_user_ids`(服务端把调用者作为
 * 群主加进去,重复传会被服务端丢掉,但确认框上的人数会因此比群里多一个)。
 *
 * 为什么要抽成纯函数(依赖用 lambda 注入):它是 App 里唯一能一次建 300 人群的路径,而
 * 分页的终止条件、上限的判定、`isSelf` 的过滤这三件事**只在错误的那个分支上才显形**
 * —— 少翻一页只是群里少几个人,没人会去数。抽出来之后这些都能在 JVM 单测里钉住
 * (见 GroupChatPlanningTest)。
 *
 * 两个防御(服务端行为异常时不至于把 App 拖住):
 * - 翻页次数上限:服务端要是永远回 `next`,循环会一直发请求下去;既然 `count` 已经
 *   保证 ≤ cap,超过 `cap / pageSize + 2` 页就是服务端自相矛盾,直接失败。
 * - 每翻一页再核一次上限:`count` 与真实返回的条数不一致时,不让成员列表真的涨过上限。
 */
suspend fun planGroupChat(
    cap: Int = GROUP_CHAT_MEMBER_CAP,
    pageSize: Int = GROUP_CHAT_PAGE_SIZE,
    // 放最后:调用点因此可以直接写成 `planGroupChat { page, pageSize -> … }`。
    fetchPage: suspend (page: Int, pageSize: Int) -> Result<MemberPage>,
): Result<GroupChatPlan> {
    val first = fetchPage(1, pageSize).getOrElse { return Result.failure(it) }
    if (first.total > cap) {
        return Result.success(GroupChatPlan.TooMany(first.total))
    }
    val ids = first.members.filterNot { it.isSelf }.map { it.id }.toMutableList()
    if (ids.size > cap) {
        return Result.success(GroupChatPlan.TooMany(ids.size))
    }
    val maxPages = (cap / pageSize) + 2
    var pages = 1
    var page = first.nextPage
    var hasMore = first.hasMore
    while (hasMore) {
        if (pages >= maxPages) {
            return Result.failure(
                IllegalStateException(
                    "directory paging did not terminate after $pages pages " +
                        "(count=${first.total})",
                ),
            )
        }
        val next = fetchPage(page, pageSize).getOrElse { return Result.failure(it) }
        ids += next.members.filterNot { it.isSelf }.map { it.id }
        pages += 1
        page = next.nextPage
        hasMore = next.hasMore
        if (ids.size > cap) {
            return Result.success(GroupChatPlan.TooMany(ids.size))
        }
    }
    return Result.success(
        if (ids.isEmpty()) GroupChatPlan.Empty else GroupChatPlan.Ready(ids),
    )
}

/**
 * 「发起群聊」这个按钮该不该显示。
 *
 * 判据是「这个部门真的有人可拉」:[DepartmentDto.memberCount] 是**直属**人数(服务端
 * annotate 的值),而当前列表最多可能因为服务端过滤规则与它不一致(它没有排除
 * `sub` 为空的账号)—— 所以两个判据取或。直属 0 人但有下级部门的部门是真实存在的
 * (人全在下级),那时屏幕上其实有人可拉,不该不给按钮。两者皆空才是「点了也只能听到
 * 一句没有成员」,那就不给(与 Web 同一克制)。
 */
fun canStartGroupChat(dept: DepartmentDto, members: List<MemberDto>): Boolean =
    dept.memberCount > 0 || members.isNotEmpty()
