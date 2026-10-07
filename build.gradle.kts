// Top-level build file. Plugin versions live in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// 设计规范护栏(docs/设计规范.md)。实现见 buildSrc/…/DesignLintTask.kt。
apply(from = "gradle/design-lint.gradle.kts")

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
