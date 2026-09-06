package com.we.meet.feature.docs.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import coil.ImageLoader
import coil.compose.AsyncImage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.feature.docs.R
import com.we.meet.ui.components.WeMeetInlineEmptyState
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.DocsColors
import com.we.meet.ui.theme.WeMeetTheme

/**
 * BlockNote JSON → Compose 阅读态渲染器(设计文档 §4.5)。
 *
 * 原则:
 *  - 宽容解析:未知块/行内一律降级占位,绝不崩;
 *  - 样式取 M3 token(标题按 15 档 type scale、容器用 surfaceContainer*);
 *  - 编辑器**内容色**(行内文字/背景色名)是用户数据,按名称运行时解析成
 *    浅/深两套取值 —— 与日历 `parseCalendarColor` 同先例(运行时解析的数据色,
 *    本来就不可能 token 化);
 *  - 唯一的新主题语义色是评论锚定高亮,取 `WeMeetTheme.extras.docs`。
 */

private val contentMoshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

private val inlineListAdapter: com.squareup.moshi.JsonAdapter<List<JsonInlineDto>> by lazy {
    contentMoshi.adapter(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, JsonInlineDto::class.java),
    )
}

/** Parses the raw BlockNote JSON string; invalid input → empty list. */
fun parseBlockNoteJson(raw: String?): List<JsonBlockDto> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val adapter = contentMoshi.adapter<List<JsonBlockDto>>(
            com.squareup.moshi.Types.newParameterizedType(List::class.java, JsonBlockDto::class.java),
        )
        adapter.fromJson(raw) ?: emptyList()
    }.getOrDefault(emptyList())
}

/**
 * Accepts either a JSON string (legacy), a parsed JSON array of BlockNote
 * blocks (the formatted-content converter returns blocks as an array), or a
 * List<Map> from an `Any?` DTO field — re-serialised to JSON then parsed.
 */
fun parseBlockNoteContent(raw: Any?): List<JsonBlockDto> = when (raw) {
    is String -> parseBlockNoteJson(raw)
    is List<*> -> runCatching {
        // `Any?` fields deserialise to LinkedHashMap; round-trip through JSON
        // so Moshi builds the typed block/inline DTOs.
        val json = contentMoshi.adapter(Any::class.java).toJson(raw)
        parseBlockNoteJson(json)
    }.getOrDefault(emptyList())
    else -> emptyList()
}

private data class FlattenedBlock(
    val block: JsonBlockDto,
    val depth: Int,
    val orderNumber: Int?,
    val pathKey: String,
)

/** Builder 上下文:把只能在组合期读取的主题值一次取齐,传给纯函数。 */
private data class InlineStyleCtx(
    val primary: Color,
    val docs: DocsColors,
    val dark: Boolean,
)

@Composable
fun DocReader(
    blocks: List<JsonBlockDto>,
    onOpenDoc: (docId: String) -> Unit,
    onOpenUrl: (url: String) -> Unit,
    onOpenWebFallback: () -> Unit,
    imageLoader: ImageLoader,
) {
    val flattened = remember(blocks) { flattenBlocks(blocks) }
    if (flattened.isEmpty()) {
        Box(Modifier.fillMaxWidth()) {
            WeMeetInlineEmptyState(
                title = stringResource(R.string.docs_reader_empty_title),
                description = stringResource(R.string.docs_reader_empty_desc),
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(flattened, key = { it.pathKey }) { item ->
            BlockView(
                item = item,
                onOpenDoc = onOpenDoc,
                onOpenUrl = onOpenUrl,
                onOpenWebFallback = onOpenWebFallback,
                imageLoader = imageLoader,
            )
        }
    }
}

/** Depth-first flatten; ordered-list numbering computed per sibling run. */
private fun flattenBlocks(blocks: List<JsonBlockDto>): List<FlattenedBlock> {
    val out = mutableListOf<FlattenedBlock>()
    fun walk(list: List<JsonBlockDto>, depth: Int, pathPrefix: String) {
        var number = 1
        list.forEachIndexed { index, block ->
            val isNumbered = block.type == "numberedListItem"
            val order = if (isNumbered) number++ else null
            out += FlattenedBlock(
                block = block,
                depth = depth,
                orderNumber = order,
                pathKey = "$pathPrefix/$index-${block.type}-${block.id.orEmpty()}",
            )
            if (block.children.isNotEmpty()) {
                walk(block.children, depth + 1, "$pathPrefix/$index")
            }
        }
    }
    walk(blocks, 0, "")
    return out
}

@Composable
private fun BlockView(
    item: FlattenedBlock,
    onOpenDoc: (docId: String) -> Unit,
    onOpenUrl: (url: String) -> Unit,
    onOpenWebFallback: () -> Unit,
    imageLoader: ImageLoader,
) {
    val block = item.block
    val baseModifier = Modifier
        .fillMaxWidth()
        .padding(
            start = Dimens.ScreenPadding + Dimens.SpaceL * item.depth,
            end = Dimens.ScreenPadding,
            top = Dimens.SpaceXs,
            bottom = Dimens.SpaceXs,
        )
    when (block.type) {
        "paragraph" -> InlineText(
            inlines = block.inlineContent(),
            baseStyle = MaterialTheme.typography.bodyLarge,
            alignment = block.textAlignment(),
            modifier = baseModifier,
            onOpenDoc = onOpenDoc,
            onOpenUrl = onOpenUrl,
        )

        "heading" -> {
            val style = headingStyle(block.props.int("level") ?: 1)
            InlineText(
                inlines = block.inlineContent(),
                baseStyle = style,
                alignment = block.textAlignment(),
                modifier = baseModifier,
                onOpenDoc = onOpenDoc,
                onOpenUrl = onOpenUrl,
            )
        }

        "bulletListItem" -> Row(
            modifier = baseModifier,
            verticalAlignment = Alignment.Top,
        ) {
            Text("•", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(Dimens.SpaceS))
            InlineText(
                inlines = block.inlineContent(),
                baseStyle = MaterialTheme.typography.bodyLarge,
                alignment = block.textAlignment(),
                modifier = Modifier.weight(1f),
                onOpenDoc = onOpenDoc,
                onOpenUrl = onOpenUrl,
            )
        }

        "numberedListItem" -> Row(
            modifier = baseModifier,
            verticalAlignment = Alignment.Top,
        ) {
            Text("${item.orderNumber ?: 1}.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(Dimens.SpaceS))
            InlineText(
                inlines = block.inlineContent(),
                baseStyle = MaterialTheme.typography.bodyLarge,
                alignment = block.textAlignment(),
                modifier = Modifier.weight(1f),
                onOpenDoc = onOpenDoc,
                onOpenUrl = onOpenUrl,
            )
        }

        "checkListItem", "todoListItem" -> Row(
            modifier = baseModifier,
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (block.props.bool("checked")) {
                    Icons.Outlined.CheckBox
                } else {
                    Icons.Outlined.CheckBoxOutlineBlank
                },
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconMedium),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Dimens.SpaceS))
            InlineText(
                inlines = block.inlineContent(),
                baseStyle = MaterialTheme.typography.bodyLarge,
                alignment = block.textAlignment(),
                modifier = Modifier.weight(1f),
                onOpenDoc = onOpenDoc,
                onOpenUrl = onOpenUrl,
            )
        }

        "codeBlock" -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = baseModifier,
        ) {
            Column(Modifier.padding(Dimens.SpaceM)) {
                val language = block.props.str("language")
                if (!language.isNullOrBlank()) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InlineText(
                    inlines = block.inlineContent(),
                    baseStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    alignment = TextAlign.Start,
                    onOpenDoc = onOpenDoc,
                    onOpenUrl = onOpenUrl,
                )
            }
        }

        "callout" -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small,
            modifier = baseModifier,
        ) {
            Row(
                Modifier.padding(Dimens.SpaceM),
                verticalAlignment = Alignment.Top,
            ) {
                val emoji = block.props.str("emoji")
                if (!emoji.isNullOrBlank()) {
                    Text(emoji, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(Dimens.SpaceS))
                }
                InlineText(
                    inlines = block.inlineContent(),
                    baseStyle = MaterialTheme.typography.bodyLarge,
                    alignment = block.textAlignment(),
                    modifier = Modifier.weight(1f),
                    onOpenDoc = onOpenDoc,
                    onOpenUrl = onOpenUrl,
                )
            }
        }

        "table" -> TableView(
            block = block,
            modifier = baseModifier,
            onOpenDoc = onOpenDoc,
            onOpenUrl = onOpenUrl,
            onOpenWebFallback = onOpenWebFallback,
        )

        "image" -> ImageView(block = block, modifier = baseModifier, imageLoader = imageLoader)

        "video", "audio", "file", "pdf" -> AttachmentCard(
            block = block,
            modifier = baseModifier,
            onOpenUrl = onOpenUrl,
        )

        "uploadLoader" -> Unit // transient upload block — nothing to render

        "columnList", "column" -> Unit // children already flattened recursively

        "pageBreak" -> Column(baseModifier) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        "quote" -> InlineText(
            inlines = block.inlineContent(),
            baseStyle = MaterialTheme.typography.bodyLarge,
            alignment = block.textAlignment(),
            modifier = baseModifier,
            onOpenDoc = onOpenDoc,
            onOpenUrl = onOpenUrl,
        )

        else -> UnknownBlock(onOpenWebFallback = onOpenWebFallback, modifier = baseModifier)
    }
}

@Composable
private fun TableView(
    block: JsonBlockDto,
    modifier: Modifier,
    onOpenDoc: (docId: String) -> Unit,
    onOpenUrl: (url: String) -> Unit,
    onOpenWebFallback: () -> Unit,
) {
    val table = runCatching {
        contentMoshi.adapter(JsonTableContentDto::class.java).fromJsonValue(block.content)
    }.getOrNull()
    if (table == null) {
        UnknownBlock(onOpenWebFallback = onOpenWebFallback, modifier = modifier)
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Column(Modifier.padding(Dimens.SpaceXs)) {
            table.rows.forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth()) {
                    row.cells.forEachIndexed { cellIndex, cell ->
                        InlineText(
                            inlines = cell,
                            baseStyle = MaterialTheme.typography.bodyMedium,
                            alignment = TextAlign.Start,
                            modifier = Modifier
                                .weight(1f)
                                .padding(Dimens.SpaceXs),
                            onOpenDoc = onOpenDoc,
                            onOpenUrl = onOpenUrl,
                        )
                        if (cellIndex < row.cells.size - 1) {
                            Box(
                                Modifier
                                    .width(Dimens.SpaceXxs)
                                    .padding(vertical = Dimens.SpaceXxs)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                    }
                }
                if (rowIndex < table.rows.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ImageView(block: JsonBlockDto, modifier: Modifier, imageLoader: ImageLoader) {
    val url = block.props.str("url")
    val caption = block.props.str("caption")
    Column(modifier) {
        if (url.isNullOrBlank()) {
            WeMeetInlineEmptyState(title = stringResource(R.string.docs_image_unavailable))
        } else {
            AsyncImage(
                model = url,
                contentDescription = caption ?: stringResource(R.string.cd_docs_image),
                modifier = Modifier.fillMaxWidth(),
                imageLoader = imageLoader,
            )
        }
        if (!caption.isNullOrBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttachmentCard(
    block: JsonBlockDto,
    modifier: Modifier,
    onOpenUrl: (url: String) -> Unit,
) {
    val url = block.props.str("url")
    val name = block.props.str("name") ?: block.props.str("caption")
    val icon = when (block.type) {
        "video", "audio" -> Icons.Outlined.PlayCircleOutline
        "pdf" -> Icons.Outlined.PictureAsPdf
        else -> Icons.Outlined.Description
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .clickable(enabled = !url.isNullOrBlank()) { url?.let(onOpenUrl) },
    ) {
        Row(
            Modifier.padding(Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconMedium),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(horizontal = Dimens.SpaceM)) {
                Text(
                    text = name ?: stringResource(R.string.docs_attachment),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.docs_attachment_open),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UnknownBlock(onOpenWebFallback: () -> Unit, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Column(Modifier.padding(Dimens.SpaceM)) {
            Text(
                text = stringResource(R.string.docs_unknown_block),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.docs_open_web),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(top = Dimens.SpaceXs)
                    .clickable(onClick = onOpenWebFallback),
            )
        }
    }
}

// ---- Inline content ----

@Composable
private fun InlineText(
    inlines: List<JsonInlineDto>,
    baseStyle: TextStyle,
    alignment: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
    onOpenDoc: (docId: String) -> Unit,
    onOpenUrl: (url: String) -> Unit,
) {
    if (inlines.isEmpty()) return
    // 组合期读取主题值,再进 remember 的非组合 lambda。
    val primary = MaterialTheme.colorScheme.primary
    val docs = WeMeetTheme.extras.docs
    val dark = WeMeetTheme.isDark
    val ctx = remember(primary, docs, dark) {
        InlineStyleCtx(primary = primary, docs = docs, dark = dark)
    }
    val annotated = remember(inlines, baseStyle, ctx) {
        buildInlineAnnotated(inlines, baseStyle, ctx, onOpenDoc, onOpenUrl)
    }
    Text(
        text = annotated,
        style = baseStyle,
        textAlign = alignment,
        modifier = modifier,
    )
}

private fun buildInlineAnnotated(
    inlines: List<JsonInlineDto>,
    baseStyle: TextStyle,
    ctx: InlineStyleCtx,
    onOpenDoc: (docId: String) -> Unit,
    onOpenUrl: (url: String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    for (inline in inlines) {
        appendInline(inline, baseStyle, ctx, onOpenDoc, onOpenUrl)
    }
}

private fun AnnotatedString.Builder.appendInline(
    inline: JsonInlineDto,
    baseStyle: TextStyle,
    ctx: InlineStyleCtx,
    onOpenDoc: (docId: String) -> Unit,
    onOpenUrl: (url: String) -> Unit,
) {
    when (inline.type) {
        "text" -> appendStyled(inline.text.orEmpty(), inline.styles, ctx)

        "link" -> {
            val href = inline.href.orEmpty()
            val nested = inline.inlineList().ifEmpty { listOf(JsonInlineDto(type = "text", text = href)) }
            withLink(
                LinkAnnotation.Url(
                    url = href,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = ctx.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) {
                for (child in nested) appendInline(child, baseStyle, ctx, onOpenDoc, onOpenUrl)
            }
        }

        "mention" -> {
            val name = inline.props.str("name") ?: inline.text.orEmpty()
            withStyle(SpanStyle(color = ctx.primary)) {
                append("@$name")
            }
        }

        "interlinkingLinkInline" -> {
            val docId = inline.props.str("docId").orEmpty()
            val title = inline.props.str("title").orEmpty()
            if (docId.isNotBlank()) {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "doc:$docId",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = ctx.primary,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                        linkInteractionListener = {
                            onOpenDoc(docId)
                            true
                        },
                    ),
                ) {
                    append(title.ifBlank { docId })
                }
            } else {
                append(title)
            }
        }

        else -> {
            // 宽容降级:content 是字符串就直接拼,是 inline 数组就递归。
            when (val content = inline.content) {
                is String -> append(content)
                is List<*> -> content.filterIsInstance<JsonInlineDto>()
                    .forEach { appendInline(it, baseStyle, ctx, onOpenDoc, onOpenUrl) }
                else -> append(inline.text.orEmpty())
            }
        }
    }
}

private fun AnnotatedString.Builder.appendStyled(
    text: String,
    styles: Map<String, Any?>,
    ctx: InlineStyleCtx,
) {
    if (text.isEmpty()) return
    val commented = styles.keys.any { it.equals("comment", ignoreCase = true) }
    val span = SpanStyle(
        fontWeight = if (styles.bool("bold")) FontWeight.Bold else null,
        fontStyle = if (styles.bool("italic")) FontStyle.Italic else null,
        textDecoration = when {
            styles.bool("underline") -> TextDecoration.Underline
            styles.bool("strike") -> TextDecoration.LineThrough
            else -> null
        },
        fontFamily = if (styles.bool("code")) FontFamily.Monospace else null,
        color = (if (commented) {
            ctx.docs.commentHighlightText
        } else {
            styles.str("textColor")?.let { contentColor(it, ctx.dark) }
        }) ?: Color.Unspecified,
        background = (if (commented) {
            ctx.docs.commentHighlight
        } else {
            styles.str("backgroundColor")?.let { contentBackground(it, ctx.dark) }
        }) ?: Color.Unspecified,
    )
    // span 里各字段已由 default 值兜底,恒非空 → 直接包样式(评论高亮/样式都走这里)。
    withStyle(span) { append(text) }
}

// 编辑器内容色是**用户数据**(BlockNote 按颜色名存),运行时按名称解析成
// 浅/深两套取值 —— 与日历 CalendarColors.parseCalendarColor 同一先例
// (运行时解析的数据色,本来就不可能 token 化,故不引入裸色值到主题)。

private val LIGHT_CONTENT_COLORS = mapOf(
    "gray" to "#6B7280",
    "brown" to "#8D6E63",
    "orange" to "#B45309",
    "yellow" to "#A16207",
    "green" to "#15803D",
    "blue" to "#1D4ED8",
    "purple" to "#7C3AED",
    "pink" to "#BE185D",
    "red" to "#DC2626",
)

private val DARK_CONTENT_COLORS = mapOf(
    "gray" to "#9CA3AF",
    "brown" to "#BCAAA4",
    "orange" to "#FBBF24",
    "yellow" to "#FDE047",
    "green" to "#4ADE80",
    "blue" to "#7BAAFB",
    "purple" to "#A78BFA",
    "pink" to "#F472B6",
    "red" to "#F87171",
)

/** 行内文字色名 → Color;`default`/未知名 → null(跟随主题)。 */
private fun contentColor(name: String, dark: Boolean): Color? {
    if (name.isBlank() || name == "default") return null
    val hex = if (dark) DARK_CONTENT_COLORS[name] else LIGHT_CONTENT_COLORS[name]
    if (hex == null) return null
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

/** 行内背景色名 → 半透明底色(压淡,保证底上文字仍可读)。 */
private fun contentBackground(name: String, dark: Boolean): Color? {
    if (name.isBlank() || name == "default") return null
    val base = contentColor(name, dark) ?: return null
    return base.copy(alpha = if (dark) 0.24f else 0.16f)
}

// ---- block helpers ----

private fun JsonBlockDto.inlineContent(): List<JsonInlineDto> =
    (content as? List<*>)?.toInlineList() ?: emptyList()

private fun JsonInlineDto.inlineList(): List<JsonInlineDto> =
    (content as? List<*>)?.toInlineList() ?: emptyList()

/**
 * `content` is typed [Any?], so Moshi deserializes the inline array into
 * `List<LinkedHashMap>` rather than [JsonInlineDto]. Convert any present
 * [JsonInlineDto] as-is; otherwise re-parse the raw maps with Moshi so the
 * typed inline DTOs (with their own nested `content`) are built recursively.
 */
private fun List<*>.toInlineList(): List<JsonInlineDto> {
    val typed = filterIsInstance<JsonInlineDto>()
    if (typed.isNotEmpty()) return typed
    return inlineListAdapter.fromJsonValue(this) ?: emptyList()
}

private fun JsonBlockDto.textAlignment(): TextAlign = when (props.str("textAlignment")) {
    "center" -> TextAlign.Center
    "right" -> TextAlign.Right
    "justify" -> TextAlign.Justify
    else -> TextAlign.Start
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    4 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}
