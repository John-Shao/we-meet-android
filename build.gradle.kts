// Top-level build file. Plugin versions live in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// 设计规范护栏(docs/设计规范.md)。实现见 buildSrc/…/DesignLintTask.kt。
tasks.register<DesignLintTask>("checkDesignTokens") {
    group = "verification"
    description = "违规数超过基线就失败(docs/设计规范.md)"
    // core-design 自己也必须扫。它的 theme/ 是 token 定义处、components/ 是
    // 组件层,规则内部已按路径豁免掉该豁免的那几条(见 DesignLintTask 的
    // isTheme / isComponents)—— 但剩下的规则照常生效。不扫的话,往共享组件
    // 里写死一句中文、或在组件内部写裸 .dp,全 App 跟着错,却没人报警。
    sources.from(
        listOf("app", "core-design", "core-directory", "feature-assistant", "feature-docs", "feature-im")
            .map { fileTree("$it/src/main") { include("**/*.kt") } },
    )
    baselineFile.set(layout.projectDirectory.file("config/design-lint-baseline.txt"))
    repoRoot.set(layout.projectDirectory)
    updateBaseline.set(providers.gradleProperty("design.baseline").isPresent)
}

// 挂进 `check`。不挂的话这个任务就得靠人记得敲 —— 一份没人跑的检查等于
// 一份文档,规矩迟早烂掉。
//
// 有意**不**挂 `assemble`:护栏只拦「比基线更差」,写新页面的过程中出现半成品
// 的裸 .dp 是常态,让每次 debug 构建都红一次,结果一定是有人把它关掉。
// 正确的摩擦点在提交与 PR,不在编辑器里。
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkDesignTokens"))
    }
}

/**
 * 装 `.githooks/` 里的钩子(把 `core.hooksPath` 指过去)。
 *
 * 钩子不能直接放 `.git/hooks/` —— 那个目录不进版本库,克隆下来就没有。
 * 所以钩子源文件版本化在 `.githooks/`,靠这个任务把 Git 指过去,跑一次即可。
 */
tasks.register<Exec>("installGitHooks") {
    group = "verification"
    description = "把 git 钩子指向 .githooks/(每个克隆跑一次)"
    commandLine("git", "config", "core.hooksPath", ".githooks")
    doLast { logger.lifecycle("core.hooksPath -> .githooks(pre-commit 已生效)") }
}
