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
    sources.from(
        listOf("app", "core-directory", "feature-assistant", "feature-im")
            .map { fileTree("$it/src/main") { include("**/*.kt") } },
    )
    baselineFile.set(layout.projectDirectory.file("config/design-lint-baseline.txt"))
    repoRoot.set(layout.projectDirectory)
    updateBaseline.set(providers.gradleProperty("design.baseline").isPresent)
}
