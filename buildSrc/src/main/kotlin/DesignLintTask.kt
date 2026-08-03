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
        sources.files
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                val parsed = parse(f.readText())
                for (rule in RULES) {
                    val n = rule.count("/$rel", parsed.code, parsed.literals)
                    if (n > 0) current["${rule.id}|$rel"] = n
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
                regressions += "  $path\n    [$ruleId] $was → $now(+${now - was}) · ${hints[ruleId]}"
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
    private class Parsed(val code: String, val literals: List<Pair<String, String>>)

    private class Rule(
        val id: String,
        val hint: String,
        /** 返回该文件的违规数。path 形如 "/app/src/main/java/…"。 */
        val count: (path: String, code: String, literals: List<Pair<String, String>>) -> Int,
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
        val RAW_TOPBAR = Regex("(?<![A-Za-z0-9_.])TopAppBar\\s*\\(")

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

        // 提示语一律用 ASCII:Windows 控制台默认 GBK 代码页,中文在这里会变成
        // 乱码 —— 护栏报错读不懂就等于没护栏。规范正文仍是中文,靠 §号索引。
        val RULES = listOf(
            Rule(
                "raw-color",
                "use MaterialTheme.colorScheme / WeMeetTheme.extras -- spec 1.1",
            ) { p, code, _ -> if (isTheme(p)) 0 else RAW_COLOR.findAll(code).count() },
            Rule(
                "raw-font-size",
                "use MaterialTheme.typography -- spec 1.2",
            ) { p, code, _ -> if (isTheme(p)) 0 else RAW_FONT.findAll(code).count() },
            Rule(
                "raw-dimen",
                "use Dimens.* instead of a bare N.dp -- spec 1.3",
            ) { p, code, _ -> if (isTheme(p)) 0 else RAW_DIMEN.findAll(code).count() },
            Rule(
                "raw-topbar",
                "use WeMeetTopBar instead of M3 TopAppBar -- spec 2",
            ) { p, code, _ -> if (isComponents(p)) 0 else RAW_TOPBAR.findAll(code).count() },
            Rule(
                "cjk-literal",
                "move the string into strings.xml, or mark // i18n-exempt -- spec 4",
            ) { _, _, literals ->
                literals.count { (text, line) ->
                    CJK.containsMatchIn(text) &&
                        !DEV_FACING.containsMatchIn(line) &&
                        I18N_EXEMPT !in line
                }
            },
        )

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
            return Parsed(code.toString(), literals)
        }
    }
}
