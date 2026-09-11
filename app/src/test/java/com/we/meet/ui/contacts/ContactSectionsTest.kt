package com.we.meet.ui.contacts

import com.we.meet.core.directory.data.DeptRefDto
import com.we.meet.core.directory.data.LetterCountDto
import com.we.meet.core.directory.data.MemberDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSectionsTest {

    private fun member(
        id: String,
        name: String,
        initial: String? = null,
        dept: String? = "销售部",
    ) = MemberDto(
        id = id,
        fullName = name,
        department = dept?.let { DeptRefDto(id = "sales", name = it) },
        initial = initial,
    )

    // ── 语言门槛 ────────────────────────────────────────────────────────────

    @Test
    fun pinyinIndexOnlyForChineseUi() {
        // 设置里存的是 zh-CN 这类带地区的标签,所以要 startsWith 而不是等值比较。
        assertTrue(pinyinIndexEnabled("zh"))
        assertTrue(pinyinIndexEnabled("zh-CN"))
        assertTrue(pinyinIndexEnabled("ZH-hans"))
        assertFalse(pinyinIndexEnabled("en"))
        assertFalse(pinyinIndexEnabled("fr"))
        assertFalse(pinyinIndexEnabled("de"))
        assertFalse(pinyinIndexEnabled("nl"))
        // 语言还没初始化时不能就先画一条索引条出来(初始化是异步的)。
        assertFalse(pinyinIndexEnabled(null))
        assertFalse(pinyinIndexEnabled(""))
    }

    // ── 字母分组 ────────────────────────────────────────────────────────────

    @Test
    fun entriesFollowTheServerOrderAndInsertHeaders() {
        val members = listOf(
            member("1", "阿宝", "A"),
            member("2", "安琪", "A"),
            member("3", "李四", "L"),
            member("4", "张三", "Z"),
            member("5", "1001", "#"),
        )

        val entries = contactEntries(members, showLetters = true)

        // 4 个字母头(A / L / Z / #)+ 5 个人。
        assertEquals(9, entries.size)
        assertEquals(ContactEntry.Letter("A"), entries[0])
        assertEquals(ContactEntry.Person(members[0]), entries[1])
        assertEquals(ContactEntry.Person(members[1]), entries[2])
        assertEquals(ContactEntry.Letter("L"), entries[3])
        assertEquals(ContactEntry.Person(members[2]), entries[4])
        assertEquals(ContactEntry.Letter("Z"), entries[5])
        assertEquals(ContactEntry.Person(members[3]), entries[6])
        // '#' 那一桶在最后 —— 顺序由服务端给,这里只是照着插头,不重排。
        assertEquals(ContactEntry.Letter("#"), entries[7])
        assertEquals(ContactEntry.Person(members[4]), entries[8])
    }

    @Test
    fun headersOnlyOncePerRunOfSameLetter() {
        val members = listOf(member("1", "李四", "L"), member("2", "李雷", "L"))
        val entries = contactEntries(members, showLetters = true)
        // 同一个字母连着出现只插一个头(李四、李雷都在 L 下)。
        assertEquals(3, entries.size)
        assertEquals(ContactEntry.Letter("L"), entries[0])
    }

    @Test
    fun withoutLettersItIsAPlainList() {
        val members = listOf(member("1", "李四", "L"), member("2", "张三", "Z"))
        val entries = contactEntries(members, showLetters = false)
        assertEquals(
            listOf(ContactEntry.Person(members[0]), ContactEntry.Person(members[1])),
            entries,
        )
    }

    @Test
    fun emptyListProducesNoEntries() {
        assertTrue(contactEntries(emptyList(), showLetters = true).isEmpty())
    }

    // ── 首字母的兜底 ────────────────────────────────────────────────────────

    @Test
    fun initialComesFromTheServerWhenPresent() {
        assertEquals("L", initialOf(member("1", "李四", "L")))
        // 服务端给的是小写也认(规范化成大写,免得出现两个 'l' 小节)。
        assertEquals("L", initialOf(member("1", "李四", "l")))
        assertEquals("#", initialOf(member("1", "1001", "#")))
    }

    @Test
    fun initialFallsBackToTheNameWhenTheServerDoesNotSendIt() {
        // 旧后端(不带 initial):拉丁名还能分组。
        assertEquals("L", initialOf(member("1", "Li Si")))
        // 汉字没有本地拼音,只能落进 '#' —— 这正好也是服务端对它们的判定,不猜。
        assertEquals("#", initialOf(member("1", "李四")))
        assertEquals("#", initialOf(member("1", "1001")))
        assertEquals("#", initialOf(member("1", "")))
        // 空白 initial 与缺失一样处理。
        assertEquals("Z", initialOf(member("1", "Zoe", "  ")))
    }

    // ── 索引条 ──────────────────────────────────────────────────────────────

    @Test
    fun alphabetSlotsAlwaysCoverAFullAlphabetInOrder() {
        val slots = alphabetSlots(
            letters = listOf(LetterCountDto("L", 3), LetterCountDto("#", 1)),
            active = "l",
        )

        assertEquals(27, slots.size)
        assertEquals("A", slots.first().letter)
        assertEquals("#", slots.last().letter)
        // 顺序固定:A…Z 再 '#' —— 位置不动,手指才有肌肉记忆。
        assertEquals(ALPHABET_ORDER, slots.map { it.letter })
    }

    @Test
    fun emptyLettersAreDisabledAndTheActiveOneIsMarked() {
        val slots = alphabetSlots(
            letters = listOf(LetterCountDto("L", 3), LetterCountDto("#", 2)),
            active = "L",
        )

        val l = slots.first { it.letter == "L" }
        assertTrue(l.enabled)
        assertTrue(l.active)
        // 已经停在 L 上时,再点它是「取消起点」。
        assertTrue(l.clearing)

        val other = slots.last()
        assertTrue(other.enabled)
        assertFalse(other.active)
        assertFalse(other.clearing)

        // 没人的字母是禁用态(而不是点了给一片空白)。
        val empty = slots.first { it.letter == "Q" }
        assertFalse(empty.enabled)
        assertEquals(0, empty.count)
    }

    @Test
    fun activeLetterIsCaseInsensitive() {
        val lower = alphabetSlots(listOf(LetterCountDto("l", 1)), active = "L").first { it.letter == "L" }
        assertTrue(lower.active)
        val upper = alphabetSlots(listOf(LetterCountDto("L", 1)), active = "l").first { it.letter == "L" }
        assertTrue(upper.active)
    }

    @Test
    fun nullActiveMeansNoStart() {
        val slots = alphabetSlots(listOf(LetterCountDto("L", 1)), active = null)
        assertTrue(slots.none { it.active })
    }
}
