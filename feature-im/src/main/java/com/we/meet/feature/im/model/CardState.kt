package com.we.meet.feature.im.model

import org.json.JSONObject

/**
 * 卡片按钮的点击结果(`content_type: card-state`,二期 A2)。
 *
 * 这是一条**非冒泡控制消息**:照常持久化+广播,但不计未读、不顶会话、不进
 * 全文搜索。客户端把它**叠在 [targetMid] 那张卡上**渲染,自己不成一行。
 *
 * 为什么不直接改原消息:jusi 改不了已发消息的 body。就算能改也不该改 ——
 * body 是机器人说的话,结果是我们记的账。把原话改写成「已同意」等于让审计
 * 链条撒谎,jusi 的全文索引里会存在一条谁都没发过的 body。
 *
 * 只有 `resolve: once` 的块会广播这条。`each` 块(重跑那类)点一百次也不该
 * 在 jusi 里留一百条控制消息。
 */
data class CardStateBody(
    val targetMid: Long,
    /** 哪个 actions 块。编号规则见 [actionsBlockKey] —— **三端契约**。 */
    val block: String,
    val buttonId: String,
    /** 广播给群里的结果文案(A2 是「谁 做了什么」)。 */
    val text: String,
)

/** 一个 actions 块的定局结果。 */
data class CardResolution(val buttonId: String, val text: String)

object CardStateParser {

    /** 宽容解析:说不通就返回 null,调用方当这条控制消息不存在。 */
    fun parse(body: String): CardStateBody? = try {
        val o = JSONObject(body)
        val mid = o.optLong("target_mid", 0L)
        val block = o.optString("block")
        if (mid <= 0L || block.isEmpty()) {
            null
        } else {
            CardStateBody(
                targetMid = mid,
                block = block,
                buttonId = o.optString("button_id"),
                text = o.optString("text"),
            )
        }
    } catch (_: Throwable) {
        null
    }
}

/**
 * 第几个 actions 块 → 服务端用的 block key(`a0`/`a1`…)。
 *
 * **这是一条三端契约**:服务端 `bot_cards.card_button_defs` 与 Web 的
 * `actionsBlockKey` 按同样的规则编号,叠加层就是按这个 key 索引的。数错一位
 * 的后果是「点了第二块,结果显示在第一块上」—— 不报错,只诡异。
 *
 * 计的是 **actions 块的序号**,不是块在数组里的下标 —— 中间夹着的
 * text/fields/divider 不占号。
 */
fun actionsBlockKey(blocks: List<CardBlock>, index: Int): String =
    "a" + blocks.take(index).count { it is CardBlock.Actions }
