import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * 设计规范护栏(见 docs/设计规范.md)。
 *
 * 为什么是「棘轮」而不是硬失败:立规矩时存量已有 1000+ 处裸 .dp,一上来就
 * 报错等于全员绕过,规矩当场作废。所以记一份基线,**只拦增量** —— 新代码
 * 必须干净,存量随缘迁移,迁一处基线降一处,不可能反弹。
 *
 *   ./gradlew checkDesignTokens                   检查(CI / 提交前)
 *   ./gradlew checkDesignTokens -Pdesign.baseline 重写基线(迁移后降数字用)
 */
abstract class DesignLintTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** 基线文件。故意不标 @InputFile —— 首次运行时它还不存在。 */
    @get:Internal
    abstract val baselineFile: RegularFileProperty

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    /** 传了 -Pdesign.baseline 时改为重写基线,不做检查。 */
    @get:Internal
    abstract val updateBaseline: Property<Boolean>

    @TaskAction
    fun run() {
        val root = repoRoot.get().asFile
        val baseline = baselineFile.get().asFile

        val current = sortedMapOf<String, Int>()
        // 只在报回归时用得上,所以按 key 顺手存着,不额外再解析一遍文件。
        val where = mutableMapOf<String, List<Int>>()
        sources.files
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                val parsed = parse(f.readText())
                for (rule in RULES) {
                    val n = rule.count("/$rel", parsed.code, parsed.literals, parsed.lines)
                    if (n > 0) {
                        current["${rule.id}|$rel"] = n
                        rule.locate?.let { where["${rule.id}|$rel"] = it(parsed.lines) }
                    }
                }
            }

        if (updateBaseline.get()) {
            baseline.parentFile.mkdirs()
            baseline.writeText(
                buildString {
                    appendLine("# 设计规范违规基线 —— 由 ./gradlew checkDesignTokens -Pdesign.baseline 生成")
                    appendLine("# 格式: 规则|文件路径|数量。数字只许降不许升,详见 docs/设计规范.md")
                    current.forEach { (k, v) -> appendLine("$k|$v") }
                },
            )
            logger.lifecycle(
                "基线已写入 ${baseline.relativeTo(root).invariantSeparatorsPath}" +
                    "(${current.size} 条,共 ${current.values.sum()} 处)",
            )
            return
        }

        if (!baseline.exists()) {
            throw GradleException(
                "缺少基线文件 ${baseline.relativeTo(root).invariantSeparatorsPath}。\n" +
                    "先跑一次:./gradlew checkDesignTokens -Pdesign.baseline",
            )
        }

        val recorded = mutableMapOf<String, Int>()
        baseline.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val idx = line.lastIndexOf('|')
                if (idx > 0) {
                    line.substring(idx + 1).trim().toIntOrNull()
                        ?.let { recorded[line.substring(0, idx)] = it }
                }
            }

        val hints = RULES.associate { it.id to it.hint }
        val regressions = mutableListOf<String>()
        current.forEach { (key, now) ->
            val was = recorded[key] ?: 0
            if (now > was) {
                val ruleId = key.substringBefore('|')
                val path = key.substringAfter('|')
                // 有行号就报行号 —— 光说「0 变成 4」等于让改的人自己再找一遍。
                val at = where[key]?.distinct()?.takeIf { it.isNotEmpty() }
                    ?.let { "\n    at lines ${it.joinToString(", ")}" }.orEmpty()
                regressions += "  $path\n    [$ruleId] $was → $now(+${now - was}) · ${hints[ruleId]}$at"
            }
        }

        if (regressions.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Design-token violations increased. See docs/设计规范.md")
                    appendLine()
                    regressions.forEach { appendLine(it) }
                    appendLine(
                        "If this is genuinely intended (rare), re-baseline with: " +
                            "./gradlew checkDesignTokens -Pdesign.baseline",
                    )
                },
            )
        }

        val improved = recorded.count { (k, was) -> (current[k] ?: 0) < was }
        if (improved > 0) {
            logger.lifecycle(
                "设计规范检查通过。有 $improved 个文件比基线更干净了 —— " +
                    "记得跑 -Pdesign.baseline 把基线降下来。",
            )
        } else {
            logger.lifecycle("设计规范检查通过(存量 ${current.values.sum()} 处待迁移)。")
        }
    }

    // ---- 扫描实现 ----------------------------------------------------------

    /**
     * @param literals 字符串字面量,连同它所在那一行的原文 —— cjk 规则要靠
     *   上下文判断这条到底是不是给用户看的。
     */
    private class Parsed(
        val code: String,
        val literals: List<Pair<String, String>>,
        /** 原始行(含注释)—— 豁免标记写在注释里,剥完注释就看不到了。 */
        val lines: List<String>,
    )

    private class Rule(
        val id: String,
        val hint: String,
        /**
         * 报违规时顺带给出行号。
         *
         * 只有「一处一行」的规则给得出 —— 给得出就给:基线只记数量,回归时光说
         * 「这个文件从 0 变成 4」而不说是哪四行,改的人得自己再 grep 一遍。
         */
        val locate: ((lines: List<String>) -> List<Int>)? = null,
        /** 返回该文件的违规数。path 形如 "/app/src/main/java/…"。 */
        val count: (
            path: String,
            code: String,
            literals: List<Pair<String, String>>,
            lines: List<String>,
        ) -> Int,
    )

    private companion object {
        val CJK = Regex("[\\u4e00-\\u9fff]")
        // `\b` 就够了,不要加前置断言:
        // - `setColor(0x…)`/`parseColor(` 里 Color 前面是词字符,没有词边界,
        //   天然不匹配 —— 那些是传统 View/Drawable 的 API,不归这条规则管;
        // - 而 `androidx.compose.ui.graphics.Color(0x…)` 这种全限定写法前面
        //   是点号,有词边界,**必须**匹配。用 `(?<![A-Za-z0-9_.])` 会把它一起
        //   放过去,等于给规则开了个后门。
        val RAW_COLOR = Regex("\\bColor\\(0[xX]")
        val RAW_FONT = Regex("\\b(fontSize|lineHeight)\\s*=\\s*[0-9]+(\\.[0-9]+)?\\.sp")
        val RAW_DIMEN = Regex("\\b[0-9]+(\\.[0-9]+)?\\.dp\\b")
        // 词边界会同时命中 `TopAppBar(` 与全限定的
        // `androidx.compose.material3.TopAppBar(`，不能让换一种 import 写法成为后门。
        val RAW_TOPBAR = Regex("\\bTopAppBar\\s*\\(")
        val ICON_BUTTON_NULL_DESCRIPTION = Regex(
            "\\bIconButton\\s*\\([\\s\\S]{0,500}?\\)\\s*\\{[^}]{0,500}?" +
                "contentDescription\\s*=\\s*null",
        )
        val UNDERSIZED_ICON_BUTTON = Regex(
            "\\bIconButton\\s*\\((?:(?!\\)\\s*\\{)[\\s\\S]){0,300}?" +
                "\\.size\\(Dimens\\." +
                "(?:IconButtonCompact|IconTiny|IconSmall|IconMedium|IconLarge)\\)",
        )

        // ---- 文字/图标色必须取前景槽位 ------------------------------------
        //
        // M3 的 ColorScheme 是**成对**的:每个「面」有一个配套的 `on-` 色,配对
        // 关系本身就是对比度保证。拿面色当文字色,对比度就是碰运气 —— 这一类
        // 我们已经踩过四次:月网格今天那格取错 on- 色(1.2:1)、非本月日期用
        // outlineVariant(1.66:1)、全仓 36 处拿 outline 当次要文字(4.44:1)、
        // 提醒角标文字和底同色(2.07:1)。
        //
        // 所以规则很直白:`color =` / `tint =` 右边**不许**出现下面这些槽位。
        val NON_FG_SLOTS = setOf(
            "background", "surface", "surfaceVariant", "surfaceTint",
            "surfaceDim", "surfaceBright", "surfaceContainer", "surfaceContainerLow",
            "surfaceContainerLowest", "surfaceContainerHigh", "surfaceContainerHighest",
            "primaryContainer", "secondaryContainer", "tertiaryContainer", "errorContainer",
            "outline", "outlineVariant", "scrim", "inverseSurface",
        )

        /**
         * 只查 `MaterialTheme.colorScheme.*`,**不查** `WeMeetTheme.extras.*`。
         *
         * extras 里没有 M3 那种系统性的 `on-` 命名(有 `onReminder`,也有
         * `connConnectedFg`、`acceptedText` 这类各叫各的),机械匹配只会误报到
         * 没人看。extras 的配对关系靠 Color.kt 的 KDoc 和走查清单守。
         */
        val SCHEME_SLOT = Regex("MaterialTheme\\.colorScheme\\.(\\w+)")
        val FG_ASSIGN = Regex("\\b(color|tint)\\s*=")

        /**
         * `color = when {` / `color = if (…) {` —— 分支体在后面几行,光看这一行
         * 看不到取的是哪个槽位。月网格那个 bug(`!inMonth -> outlineVariant`)
         * 和群资料的占位文字(`if (blank) { outline }`)都是这么藏住的。
         */
        val FG_BRANCH = Regex("\\b(color|tint)\\s*=\\s*(when|if)\\b")

        /**
         * 这些上下文里的 `color =` 不是文字色,放过。
         *
         * 两条最要紧:
         * - `background(color = …)` —— 和文字色写法一模一样,不排掉的话每个
         *   铺底都会误报。
         * - `Surface(color = …)` —— M3 的 Surface 拿 `color` 当**底色**(前景走
         *   `contentColor`)。聊天气泡、表态 chip、群头像兜底全是这个写法,
         *   不排掉的话这条规则报出来的绝大多数都是底色,很快就没人看了。
         *
         * 分隔线/描边/进度条/Canvas 同理 —— 它们本来就该用 outline 那几档。
         */
        val NON_FG_CONTEXT = Regex(
            "\\.background\\(|\\bbackground\\s*=|Divider\\(|DividerDefaults|" +
                "(?<![A-Za-z0-9_])Surface\\s*\\(|" +
                "\\.border\\(|ProgressIndicator\\(|Canvas\\(|draw[A-Z]\\w*\\(|" +
                "containerColor|\\bBrush\\.|\\.drawBehind\\b",
        )

        /**
         * 这一行的 `color =` 是不是长在「不是文字色」的调用里。
         *
         * 判断靠**缩进**找外层调用,而不是往上数固定行数:`Surface(` 到它的
         * `color =` 之间可以隔着十几行别的参数,数行数要么漏(窗口小)要么误伤
         * (窗口大,把上一个不相干的调用也算进来)。缩进比它浅、且带 `(` 的
         * 第一行,就是包着它的那个调用。
         *
         * 对齐得不规范的代码会判错 —— 那是棘轮基线兜底的部分,不值得为它上
         * 真解析器。
         */
        fun inNonFgContext(lines: List<String>, idx: Int): Boolean {
            val line = lines[idx]
            if (NON_FG_CONTEXT.containsMatchIn(line)) return true
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (indent <= 0) return false
            for (i in idx - 1 downTo 0) {
                val l = lines[i]
                val t = l.indexOfFirst { !it.isWhitespace() }
                if (t in 0 until indent && '(' in l) return NON_FG_CONTEXT.containsMatchIn(l)
            }
            return false
        }

        // ---- 下拉菜单不许跟着窄锚点收窄 ------------------------------------
        //
        // `ExposedDropdownMenu` 的 `matchTextFieldWidth` 默认 true —— 它的设计
        // 前提是锚点为一个 fillMaxWidth 的输入框。锚点换成 TextButton(「不重复
        // ▲」这种)时,菜单被强行约束成按钮那么窄,「每个工作日」当场折成两行。
        //
        // 所以:Box 里没有输入框当锚点的,必须**显式**给出 matchTextFieldWidth。
        // 写 true 也算过 —— 这条要的是「想过这件事」,不是某个固定值。
        val EDM_BOX = Regex("(?<![A-Za-z0-9_.])ExposedDropdownMenuBox\\s*\\(")
        val EDM_MENU = Regex("(?<![A-Za-z0-9_.])ExposedDropdownMenu\\s*\\(")
        val EDM_ANCHOR_FIELD = Regex("TextField\\s*\\(")
        const val MATCH_WIDTH = "matchTextFieldWidth"

        /** theme 包是 token 的定义处,色值/字号字面量本就该写在那。 */
        fun isTheme(path: String) = "/theme/" in path

        /** components 是组件层,允许包装 M3 原生组件。 */
        fun isComponents(path: String) = "/ui/components/" in path

        /**
         * 这一行里的中文是不是「不该进 strings.xml」的。
         *
         * 两类豁免:
         * 1. **开发者可见** —— `require`/`check`/`error`/异常消息/日志。这些
         *    永远不会显示给用户,搬进 strings.xml 反而是噪音。
         * 2. **故意不翻译** —— 语言选择器里的「简体中文」「Français」之类,
         *    每种语言都用它自己的写法才对。用 `// i18n-exempt` 标记,
         *    强制写清理由。
         *
         * 不加豁免的话规则会一直报这些,报到没人看 —— 那护栏就白建了。
         */
        val DEV_FACING = Regex(
            """\b(require|check|checkNotNull|error|TODO)\s*\(|""" +
                """\bLog\.[dviwe]\s*\(|Exception\(|\bassert\b""",
        )
        const val I18N_EXEMPT = "i18n-exempt"

        /**
         * 结构性豁免标记 `// design-exempt: 理由`。
         *
         * 给「确实不该套共享组件」的地方用 —— 比如把搜索输入框放在标题位的
         * 顶栏,那是另一种组件,硬塞进 [WeMeetTopBar] 会毁掉它「标题单行截断」
         * 的保证。**必须写理由**,否则它就成了绕过规则的后门。
         *
         * 标记可以写在该行上,或**紧邻其上的那段注释**里的任意一行 —— 往上
         * 一直找到第一个非注释行为止。不用固定的「往上 N 行」:理由通常要写
         * 好几行,写多写少不该影响它认不认。
         */
        const val DESIGN_EXEMPT = "design-exempt"

        fun isExempt(lines: List<String>, idx: Int): Boolean {
            if (DESIGN_EXEMPT in lines.getOrElse(idx) { "" }) return true
            var i = idx - 1
            while (i >= 0) {
                val t = lines[i].trim()
                if (!t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")) return false
                if (DESIGN_EXEMPT in t) return true
                i--
            }
            return false
        }

        // 提示语一律用 ASCII:Windows 控制台默认 GBK 代码页,中文在这里会变成
        // 乱码 —— 护栏报错读不懂就等于没护栏。规范正文仍是中文,靠 §号索引。
        val RULES = listOf(
            Rule(
                "raw-color",
                "use MaterialTheme.colorScheme / WeMeetTheme.extras -- spec 1.1",
            ) { p, code, _, _ -> if (isTheme(p)) 0 else RAW_COLOR.findAll(code).count() },
            Rule(
                "raw-font-size",
                "use MaterialTheme.typography -- spec 1.2",
            ) { p, code, _, _ -> if (isTheme(p)) 0 else RAW_FONT.findAll(code).count() },
            Rule(
                "raw-dimen",
                "use Dimens.* instead of a bare N.dp -- spec 1.3",
            ) { p, code, _, _ -> if (isTheme(p)) 0 else RAW_DIMEN.findAll(code).count() },
            Rule(
                "raw-topbar",
                "use WeMeetTopBar instead of M3 TopAppBar -- spec 2",
            ) { p, _, _, lines ->
                if (isComponents(p)) {
                    0
                } else {
                    // 按行数,才能看它上面几行有没有豁免注释。
                    lines.withIndex().sumOf { (i, line) ->
                        val hits = RAW_TOPBAR.findAll(line).count()
                        if (hits == 0 || isExempt(lines, i)) 0 else hits
                    }
                }
            },
            Rule(
                "text-color-slot",
                "text/icon color must be an on-* slot (or primary/error accent), " +
                    "not a surface/container/outline slot -- spec 1.1",
                locate = { lines -> textColorMisuseLines(lines) },
            ) { p, _, _, lines ->
                if (isTheme(p)) 0 else textColorMisuseLines(lines).size
            },
            Rule(
                "dropdown-menu-width",
                "ExposedDropdownMenu on a non-TextField anchor must pass " +
                    "matchTextFieldWidth explicitly, else options wrap -- spec 2.3",
                locate = { lines -> dropdownWidthMisuseLines(lines) },
            ) { _, _, _, lines -> dropdownWidthMisuseLines(lines).size },
            Rule(
                "icon-button-description",
                "functional IconButton icons need a non-null contentDescription -- spec 5.1",
                locate = { lines -> regexHitLines(lines, ICON_BUTTON_NULL_DESCRIPTION) },
            ) { _, code, _, _ -> ICON_BUTTON_NULL_DESCRIPTION.findAll(code).count() },
            Rule(
                "icon-button-touch-target",
                "IconButton touch targets must use Dimens.MinTouchTarget (48dp) -- spec 5.2",
                locate = { lines -> regexHitLines(lines, UNDERSIZED_ICON_BUTTON) },
            ) { _, code, _, _ -> UNDERSIZED_ICON_BUTTON.findAll(code).count() },
            Rule(
                "cjk-literal",
                "move the string into strings.xml, or mark // i18n-exempt -- spec 4",
            ) { _, _, literals, _ ->
                literals.count { (text, line) ->
                    CJK.containsMatchIn(text) &&
                        !DEV_FACING.containsMatchIn(line) &&
                        I18N_EXEMPT !in line
                }
            },
        )

        fun regexHitLines(lines: List<String>, regex: Regex): List<Int> {
            val code = lines.joinToString("\n")
            return regex.findAll(code).map { match ->
                code.take(match.range.first).count { it == '\n' } + 1
            }.toList()
        }

        /**
         * 数这个文件里「拿面色当文字色」的处数。
         *
         * 两种写法都要认:
         *
         * 1. 直接赋值 —— `color = MaterialTheme.colorScheme.outline`。
         * 2. **when 分支** —— 月网格那个 bug 就藏在这儿:
         *
         *        color = when {
         *            isToday -> …onPrimaryContainer
         *            !inMonth -> …outlineVariant     // ← 只看 `color =` 那行看不见
         *        }
         *
         *    所以进了 `color = when {` 就一路盯到这个块闭合,块内每个面色都算。
         *    括号深度用 `{` `}` 数 —— 够用了,不为一条棘轮规则写 Kotlin 解析器。
         *
         * 用**原始行**(含注释)而不是挖空后的 code:豁免标记写在注释里。代价是
         * 注释里出现 `color = MaterialTheme.colorScheme.surface` 这样的示例会被
         * 算进去 —— 真遇上了写 `// design-exempt: 这是注释里的示例` 即可。
         */
        fun textColorMisuseLines(lines: List<String>): List<Int> {
            val hits = mutableListOf<Int>()
            var depth = 0
            // 不在 color/tint 的分支块里时为 null;在的话记块开始时的深度。
            var branchAt: Int? = null
            // 分支块的第一行(`color = when {` 那行)—— 上下文判断要看它,
            // 而不是看分支体所在的行,否则 `.background(color = when {…})`
            // 里的每个分支都会误报。
            var branchHead = -1
            lines.forEachIndexed { i, line ->
                val before = depth
                val opensBranch = FG_BRANCH.containsMatchIn(line)
                val inBranch = branchAt != null
                // 分支块内每一行都查;块外只查带 color=/tint= 的行。
                if ((inBranch || FG_ASSIGN.containsMatchIn(line)) && !isExempt(lines, i)) {
                    val n = SCHEME_SLOT.findAll(line).count { it.groupValues[1] in NON_FG_SLOTS }
                    val ctxAt = if (inBranch) branchHead else i
                    if (n > 0 && !inNonFgContext(lines, ctxAt)) repeat(n) { hits += i + 1 }
                }
                depth += line.count { it == '{' } - line.count { it == '}' }
                when {
                    // 真开了块才盯下去。`color = if (a) X else Y` 写在一行里
                    // 深度不变,同一行已经查过了,不要留下没人关的标记。
                    opensBranch && depth > before -> {
                        branchAt = before
                        branchHead = i
                    }
                    // 深度回到进块前 → 块结束。
                    !opensBranch -> branchAt?.let { if (depth <= it) branchAt = null }
                }
            }
            return hits
        }

        /**
         * 数这个文件里「菜单宽度绑死在窄锚点上」的处数。
         *
         * 一个 `ExposedDropdownMenuBox { … }` 块整体判:块里出现过 `*TextField(`
         * 就认为锚点是输入框,跟宽是本分,放过;否则块里必须出现
         * `matchTextFieldWidth`,不然块内每个 `ExposedDropdownMenu(` 都算一处。
         *
         * 按块判而不是按单行判,是因为「锚点是什么」这个信息根本不在
         * `ExposedDropdownMenu(` 那一行上 —— 它在十几行之前的锚点那里。
         * 块尾同样用 `{` `}` 数深度,和 [textColorMisuseLines] 一个路子。
         */
        fun dropdownWidthMisuseLines(lines: List<String>): List<Int> {
            val hits = mutableListOf<Int>()
            var i = 0
            while (i < lines.size) {
                if (!EDM_BOX.containsMatchIn(lines[i])) {
                    i++
                    continue
                }
                var depth = 0
                var entered = false
                var end = lines.size - 1
                for (j in i until lines.size) {
                    depth += lines[j].count { it == '{' } - lines[j].count { it == '}' }
                    if (depth > 0) entered = true
                    if (entered && depth <= 0) {
                        end = j
                        break
                    }
                }
                val body = lines.subList(i, end + 1)
                val anchoredOnField = body.any { EDM_ANCHOR_FIELD.containsMatchIn(it) }
                if (!anchoredOnField && body.none { MATCH_WIDTH in it }) {
                    body.forEachIndexed { k, line ->
                        if (EDM_MENU.containsMatchIn(line) && !isExempt(lines, i + k)) {
                            hits += i + k + 1
                        }
                    }
                }
                i = end + 1
            }
            return hits
        }

        /**
         * 把注释挖空(保留换行,行号才准),同时把字符串字面量单独收出来。
         *
         * 必须先剥注释再匹配:本仓库注释是中文写的,里面出现「16.dp」「Color(0x…)」
         * 是家常便饭,不剥就全是误报。
         */
        fun parse(src: String): Parsed {
            val code = StringBuilder(src.length)
            val literals = mutableListOf<Pair<String, String>>()
            // 字面量要连它所在那一行的**原文**一起记(含注释)—— cjk 规则靠行
            // 上下文判豁免,而 code 里注释已经被挖空了,拿不到 // i18n-exempt。
            val srcLines = src.split("\n")
            var line = 0
            var i = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    // 行注释
                    c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                        while (i < src.length && src[i] != '\n') {
                            code.append(' ')
                            i++
                        }
                    }
                    // 块注释(含 KDoc)
                    c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                        while (i < src.length &&
                            !(src[i] == '*' && i + 1 < src.length && src[i + 1] == '/')
                        ) {
                            if (src[i] == '\n') line++
                            code.append(if (src[i] == '\n') '\n' else ' ')
                            i++
                        }
                        if (i < src.length) {
                            code.append("  ")
                            i += 2
                        }
                    }
                    // 原始字符串
                    c == '"' && src.startsWith("\"\"\"", i) -> {
                        // 结束符不能用「第一个 """」找 —— 内容本身以引号收尾时
                        // (如 `""""firstName"…([^"]*)""""`)末尾会是 4 连引号,
                        // 取第一个 """ 会早停一格,漏下一个游离的 " 把整份文件
                        // 之后的解析全带偏。Kotlin 的规则是:连续引号里最后 3 个
                        // 才是结束符,前面的都算内容。
                        var j = i + 3
                        var closeAt = -1
                        var runEnd = src.length
                        while (j < src.length) {
                            if (src[j] == '"') {
                                var k = j
                                while (k < src.length && src[k] == '"') k++
                                if (k - j >= 3) {
                                    closeAt = k - 3
                                    runEnd = k
                                    break
                                }
                                j = k
                            } else {
                                j++
                            }
                        }
                        if (closeAt < 0) {
                            closeAt = src.length
                            runEnd = src.length
                        }
                        literals += src.substring(i + 3, closeAt) to srcLines.getOrElse(line) { "" }
                        for (k in i until minOf(runEnd, src.length)) {
                            if (src[k] == '\n') line++
                            code.append(if (src[k] == '\n') '\n' else ' ')
                        }
                        i = runEnd
                    }
                    // 普通字符串
                    c == '"' -> {
                        val sb = StringBuilder()
                        val startLine = line
                        code.append(' ')
                        i++
                        while (i < src.length && src[i] != '"') {
                            if (src[i] == '\\' && i + 1 < src.length) {
                                sb.append(src[i + 1])
                                code.append("  ")
                                i += 2
                            } else {
                                if (src[i] == '\n') line++
                                sb.append(src[i])
                                code.append(if (src[i] == '\n') '\n' else ' ')
                                i++
                            }
                        }
                        if (i < src.length) {
                            code.append(' ')
                            i++
                        }
                        literals += sb.toString() to srcLines.getOrElse(startLine) { "" }
                    }
                    else -> {
                        if (c == '\n') line++
                        code.append(c)
                        i++
                    }
                }
            }
            return Parsed(code.toString(), literals, srcLines)
        }
    }
}
