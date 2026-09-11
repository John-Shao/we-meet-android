package com.we.meet.ui.contacts

import com.we.meet.core.directory.data.MemberDto

/**
 * 通讯录列表的分组 —— **纯函数**,不碰 Android/Compose,所以能直接在 JVM 单元测试里
 * 钉住(见 ContactSectionsTest)。
 *
 * 服务端已经按拼音排好序(`?ordering=pinyin`),这里只做一件事:把名字按首字母切成
 * 小节。**不重排、不算拼音** —— 客户端自己算一套「首字母」,和服务端那套迟早会在
 * 某个人身上分叉(改个名字、换一次 pypinyin 版本),而分叉的表现是「字母头写着 L,
 * 排的顺序却是另一回事」这种没人能查明白的错。
 */

/** A–Z 之外的那一桶(数字/符号/空名字)。与后端 `core/services/pinyin.py` 的 `OTHER_INITIAL` 一致。 */
const val OTHER_INITIAL = "#"

/**
 * 字母小节头开不开 —— **只在界面语言是简体中文时**。
 *
 * 理由:按拼音分桶是给中文名册用的读法。界面是英文/法文/荷兰文的组织里,一串 A–Z
 * 小节加一个「其他(数字或符号)」桶解释不了这份名册;这类用户也不按拼音找中文名,
 * 他们有搜索。
 *
 * (曾经的右侧 A–Z 索引条已经去掉:一列 27 行的小竖条在手机上既挤又突兀,而它换来的
 * 「跳到一个字母」这一步,名册的筛选已经能替代。)
 *
 * 用 `startsWith` 而不是等值比较:设置里存的是 `zh-CN` 这类带地区的标签。
 */
fun letterHeadersEnabled(languageTag: String?): Boolean =
    languageTag?.startsWith("zh", ignoreCase = true) == true

/** 列表里的一行:字母小节头,或一个人。 */
sealed interface ContactEntry {
    /** 小节头。同一个字母只出现一次(服务端已把同字母的人排在一起)。 */
    data class Letter(val initial: String) : ContactEntry

    data class Person(val member: MemberDto) : ContactEntry
}

/**
 * 把(已排好序的)成员列表切成「字母头 + 人」的行序列。
 *
 * [showLetters] 为 false(界面语言不是中文)时就是原来的平铺列表 —— 没有字母头
 * 也说得通,因为那时用户是按姓名/搜索找人的。
 *
 * 只在**连续**的人之间插头:万一服务端返回的顺序里同一个字母被拆成两段,顶多
 * 出现两个同名小节头,不会把行序打乱。
 */
fun contactEntries(members: List<MemberDto>, showLetters: Boolean): List<ContactEntry> {
    if (!showLetters) return members.map(ContactEntry::Person)
    val entries = ArrayList<ContactEntry>(members.size + LETTER_GROUP_HEADROOM)
    var current: String? = null
    for (member in members) {
        val initial = initialOf(member)
        if (initial != current) {
            entries += ContactEntry.Letter(initial)
            current = initial
        }
        entries += ContactEntry.Person(member)
    }
    return entries
}

/**
 * 一个人的首字母:优先用服务端下发的 [MemberDto.initial]。
 *
 * 服务端没给(旧后端 / 这个字段是后来才加的)时按姓名首字符兜底:拉丁名照样能分组,
 * 汉字与数字落到 '#' 桶 —— 这正好也是服务端对它们的判定。兜底只为了让字母头在
 * 后端升级之前也不至于所有人挤在一桶里,不做任何拼音猜测。
 */
fun initialOf(member: MemberDto): String {
    val fromServer = member.initial?.trim()?.uppercase()
    if (!fromServer.isNullOrEmpty()) return fromServer
    val first = member.displayName.firstOrNull()?.uppercaseChar() ?: return OTHER_INITIAL
    return if (first in 'A'..'Z') first.toString() else OTHER_INITIAL
}

/** 预分配行数时给字母头留的余量(A–Z + '#')。 */
private const val LETTER_GROUP_HEADROOM = 27
