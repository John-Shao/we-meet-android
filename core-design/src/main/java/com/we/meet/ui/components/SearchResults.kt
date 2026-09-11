package com.we.meet.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.we.meet.design.R
import com.we.meet.ui.theme.Dimens

/**
 * 搜索结果计数:「找到 N 个结果」。
 *
 * 原先全 App 只有任务搜索报数量,其余四处(联系人/会议/消息/文档)搜完一片
 * 安静 —— 用户分不清「只有这两条」和「还有更多没显示」,也分不清「搜完了」
 * 和「还在搜」。数量是这三个状态里最便宜的那个区分手段。
 *
 * 放在结果区顶部而不是塞进分区标题里:一个搜索页可能同时有多个分区
 * (「全部」分类下就是),分区各自报数会变成一串互相打架的数字。
 *
 * 因此**只在单分类视图里用** —— 那时选中的 chip 已经说明了类别,分区标题是
 * 重复的,换成计数正好。「全部」下各分区保留标题、不报数。
 *
 * 搜索**失败**时不要渲染它:「找到 0 个结果」会把「请求挂了」说成「确实没有」。
 *
 * @param count 命中总数。
 */
@Composable
fun SearchResultsHeader(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = pluralStringResource(R.plurals.common_search_results, count, count),
        modifier = modifier.padding(Dimens.SpaceL),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * 把 [text] 中命中 [query] 的片段标成主题主色。
 *
 * 结果行不高亮命中片段,用户就得自己逐字比对「我输的字到底匹配在哪」——
 * 联系人按邮箱命中、群里搜到成员名这类场景尤其难认。四处搜索原先都没做,
 * 于是「匹配哪了」全靠猜。
 *
 * 只做大小写无关的字面匹配,不做分词或拼音:高亮的职责是**复述**用户输入,
 * 匹配逻辑变了高亮就会骗人,所以这里刻意比后端搜索更笨。
 *
 * @param text 原始文本(姓名、群名、消息摘要)。
 * @param query 用户输入;空白或无命中时原样返回。
 */
@Composable
fun highlightMatches(text: String, query: String): AnnotatedString {
    val needle = query.trim()
    val accent = MaterialTheme.colorScheme.primary
    return remember(text, needle, accent) {
        if (needle.isEmpty() || text.length < needle.length) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                var cursor = 0
                while (cursor <= text.length - needle.length) {
                    val hit = text.indexOf(needle, startIndex = cursor, ignoreCase = true)
                    if (hit < 0) break
                    if (hit > cursor) append(text.substring(cursor, hit))
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(hit, hit + needle.length))
                    }
                    cursor = hit + needle.length
                }
                if (cursor < text.length) append(text.substring(cursor))
            }
        }
    }
}
