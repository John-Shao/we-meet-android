package com.we.meet.ui.contacts

import com.we.meet.core.directory.data.LetterCountDto
import com.we.meet.core.directory.data.MemberDto

/**
 * 通讯录列表的分组与索引条 —— **纯函数**,不碰 Android/Compose,所以能直接在
 * JVM 单元测试里钉住(见 ContactSectionsTest)。
 *
 * 服务端已经按拼音排好序(`?ordering=pinyin`),这里只做两件事:把名字按首字母
 * 切成小节、算出索引条上每个字母可不可点。**不重排、不算拼音** —— 客户端自己算
 * 一套「首字母」,和服务端那套迟早会在某个人身上分叉(改个名字、换一次 pypinyin
 * 版本),而分叉的表现是「索引条上说 L 有 3 个人,点进去只有 2 个」这种没人能查明白的错。
 */

/** A–Z 之外的那一桶(数字/符号/空名字)。与后端 `core/services/pinyin.py` 的 `OTHER_INITIAL` 一致。 */
const val OTHER_INITIAL = "#"

private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** 索引条上的字母顺序:A–Z,最后是 '#'(与列表里那一桶的位置一致)。 */
val ALPHABET_ORDER: List<String> = LETTERS.map(Char::toString) + OTHER_INITIAL

/**
 * 拼音索引开不开 —— **只在界面语言是简体中文时**。
 *
 * 与 Web 端同一套理由:按拼音分桶是给中文名册用的读法。界面是英文/法文/荷兰文的
 * 组织里,一列 A–Z 加一个「其他(数字或符号)」桶既解释不了这份名册,又占着手机
 * 屏幕本就很窄的右边缘;这类用户也不按拼音找中文名,他们有搜索。
 *
 * 用 `startsWith` 而不是等值比较:设置里存的是 `zh-CN` 这类带地区的标签。
 */
fun pinyinIndexEnabled(languageTag: String?): Boolean =
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
 * [showLetters] 为 false(索引关闭:界面语言不是中文)时就是原来的平铺列表 ——
 * 没有字母头也说得通,因为那时用户是按姓名/搜索找人的。
 *
 * 只在**连续**的人之间插头:万一服务端返回的顺序里同一个字母被拆成两段,顶多
 * 出现两个同名小节头,不会把行序打乱。
 */
fun contactEntries(members: List<MemberDto>, showLetters: Boolean): List<ContactEntry> {
    if (!showLetters) return members.map(ContactEntry::Person)
    val entries = ArrayList<ContactEntry>(members.size + ALPHABET_ORDER.size)
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
 * 汉字与数字落到 '#' 桶 —— 这正好也是服务端对它们的判定。兜底只为了让索引条在
 * 后端升级之前也不至于「所有字母都是空的」,不做任何拼音猜测。
 */
fun initialOf(member: MemberDto): String {
    val fromServer = member.initial?.trim()?.uppercase()
    if (!fromServer.isNullOrEmpty()) return fromServer
    val first = member.displayName.firstOrNull()?.uppercaseChar() ?: return OTHER_INITIAL
    return if (first in 'A'..'Z') first.toString() else OTHER_INITIAL
}

/**
 * 索引条要渲染的一行:字母 + 这个字母有几个人 + 能不能点。
 *
 * 没人(`count == 0`)的字母**禁用**而不是点了给一片空白 —— 后者会让用户以为
 * 「点了没反应」,而真正的原因是这一册里就没有这个字母开头的人。
 */
data class AlphabetSlot(
    val letter: String,
    val count: Int,
    val active: Boolean,
) {
    val enabled: Boolean get() = count > 0

    /**
     * 点它是「跳到这个字母」还是「取消起点、回到整册」—— 同一个字母再点一次
     * 就是取消(Web 端也是这个手感)。
     */
    val clearing: Boolean get() = active
}

/**
 * 把服务端的字母计数对齐到固定的 A–Z + '#' 顺序。
 *
 * 服务端只返回**有人的**字母,而且顺序由它自己定;索引条要的是稳定的一列
 * (字母位置固定,肌肉记忆才有用),所以在这里补齐。
 */
fun alphabetSlots(
    letters: List<LetterCountDto>,
    active: String?,
): List<AlphabetSlot> {
    val counts = letters.associate { it.letter.uppercase() to it.count }
    return ALPHABET_ORDER.map { letter ->
        AlphabetSlot(
            letter = letter,
            count = counts[letter] ?: 0,
            active = letter.equals(active, ignoreCase = true),
        )
    }
}
