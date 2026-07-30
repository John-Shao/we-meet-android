package com.we.meet.ui.calendar

import kotlin.math.ceil
import kotlin.math.min

/**
 * 共同空闲(纯函数,无 Compose 依赖):忙闲对比页 / 会话日历用。
 *
 * **口径与 Web 的 `features/calendar/utils/freeSlots.ts` 一一对应** —— 同一份
 * 输入必须给出同一批建议,双端不能各算一套。改这里请同步改那边。
 *
 * 时间一律用「当日分钟制」[0,1440] 表达:调用方已经把 freebusy 的绝对时刻投
 * 影到某一天上(跨天区间自行裁剪),这里不碰时区。
 */

/** 一段忙碌(当日分钟制,半开区间 [startMin, endMin))。 */
data class BusyRange(val startMin: Int, val endMin: Int)

/** 一个人在当日的忙碌区间(已裁剪到当日)。 */
data class PersonBusyMinutes(val userId: String, val busy: List<BusyRange>)

/** 建议时段(分钟制)。 */
data class SuggestedSlot(val startMin: Int, val endMin: Int)

private const val SLOT_MIN = 30
private const val WORK_START_MIN = 9 * 60
private const val WORK_END_MIN = 18 * 60

/** 距 now 不足该余量的时段不建议(马上开始 / 已过去)。 */
private const val IMMINENT_MARGIN_MIN = 15

/** 区间重叠(半开区间:start < otherEnd && end > otherStart)。 */
private fun overlaps(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
    aStart < bEnd && aEnd > bStart

/**
 * 所选时段与谁冲突 —— 返回忙碌成员 id(空 = 所有参与者都有空)。直接用原始
 * 区间判定,不走 30min 网格,避免吸附误差(与 Web busyPeopleInRange 同口径)。
 */
fun busyPeopleInRange(
    people: List<PersonBusyMinutes>,
    startMin: Int,
    endMin: Int,
): List<String> = people
    .filter { p -> p.busy.any { overlaps(it.startMin, it.endMin, startMin, endMin) } }
    .map { it.userId }

/**
 * 工作时间(本地 09:00–18:00)内建议 2–3 个全员空闲时段。
 *
 * 算法(同 Web):30min 网格 → 每格全员是否空闲(当天再滤掉 now+15min 之前
 * 结束的格)→ 极大连续空闲段 → 按期望时长(默认 60min,排不下降级 30min)切
 * 候选窗 → 评分(黄金时段 +2;所在段前后冗余每 30min +1 封顶 +2;距 now 每
 * 小时 -0.1 使同分先近后远)→ 贪心取互不重叠的前 [limit] 个,按时间排序。
 *
 * [nowMinuteOfDay] 传当天的「现在」(非今天传 null = 不做临近过滤)。
 */
fun suggestCommonSlots(
    people: List<PersonBusyMinutes>,
    durationMin: Int = 60,
    limit: Int = 3,
    nowMinuteOfDay: Int? = null,
): List<SuggestedSlot> {
    val slotCount = (WORK_END_MIN - WORK_START_MIN) / SLOT_MIN
    fun slotStart(i: Int) = WORK_START_MIN + i * SLOT_MIN

    val minFreeEnd = nowMinuteOfDay?.plus(IMMINENT_MARGIN_MIN)
    val allFree = BooleanArray(slotCount) { i ->
        val s = slotStart(i)
        val e = slotStart(i + 1)
        when {
            minFreeEnd != null && e <= minFreeEnd -> false // 已过去 / 马上开始
            else -> busyPeopleInRange(people, s, e).isEmpty()
        }
    }

    // 极大连续空闲段 [from, to)(格下标)。
    val runs = mutableListOf<IntRange>()
    var runFrom = -1
    for (i in 0..slotCount) {
        val free = i < slotCount && allFree[i]
        if (free && runFrom < 0) runFrom = i
        if (!free && runFrom >= 0) {
            runs += runFrom until i
            runFrom = -1
        }
    }
    if (runs.isEmpty()) return emptyList()

    // 期望时长排不下时降级到 30min。
    val longestRunMin = runs.maxOf { (it.last + 1 - it.first) * SLOT_MIN }
    val winSlots = maxOf(
        1,
        ceil(min(durationMin, longestRunMin).toDouble() / SLOT_MIN).toInt(),
    )

    data class Candidate(
        val startMin: Int,
        val endMin: Int,
        val fromSlot: Int,
        val toSlot: Int,
        val score: Double,
    )

    // 黄金时段:10–12 点、14–17 点(与 Web 同)。
    fun golden(startMin: Int): Boolean {
        val h = startMin / 60.0
        return (h >= 10 && h < 12) || (h >= 14 && h < 17)
    }

    val candidates = mutableListOf<Candidate>()
    for (run in runs) {
        val to = run.last + 1
        var s = run.first
        while (s + winSlots <= to) {
            val startMin = slotStart(s)
            val surplusSlots = to - run.first - winSlots
            var score = 0.0
            if (golden(startMin)) score += 2
            score += min(surplusSlots, 2)
            if (nowMinuteOfDay != null) score -= (startMin - nowMinuteOfDay) / 60.0 * 0.1
            candidates += Candidate(
                startMin = startMin,
                endMin = slotStart(s + winSlots),
                fromSlot = s,
                toSlot = s + winSlots,
                score = score,
            )
            s += 1
        }
    }

    val picked = mutableListOf<Candidate>()
    for (c in candidates.sortedByDescending { it.score }) {
        if (picked.size >= limit) break
        if (picked.any { c.fromSlot < it.toSlot && c.toSlot > it.fromSlot }) continue
        picked += c
    }
    return picked.sortedBy { it.startMin }.map { SuggestedSlot(it.startMin, it.endMin) }
}
