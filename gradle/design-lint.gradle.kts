// Shared by the Android build and the standalone CI settings.
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
