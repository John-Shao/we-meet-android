plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.we.meet.feature.im"
    compileSdk = 34

    defaultConfig {
        // Match the host app and :feature-assistant — bumped to 29 for audio/realtime APIs
        // we may share down the road.
        minSdk = 29
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all {
            // 卡片契约测试读的是 **we-meet 后端仓库里**那批金标准 fixture ——
            // 同一批文件后端 pytest 和 Web vitest 也在读。不拷贝一份进本仓:
            // 拷贝会各自漂,而漂了正是这个测试要拦的东西。
            //
            // 默认按双仓并列 checkout 解析(d:/…/we-meet 与 d:/…/we-meet-android)。
            // 布局不同就传 -PimCardFixtures=<绝对路径>。目录不存在时测试**直接失败**
            // 而不是跳过 —— 静默跳过等于把契约测试变成一个永远绿的空壳。
            it.systemProperty("imCardFixtures", imCardFixtureDir)
            // @所有人 别名表的契约测试要逐个 locale 比对 im_mention_everyone,
            // 而 JVM 单测拿不到 R.string —— 直接读 res 目录的 XML。
            it.systemProperty("featureImResDir", file("src/main/res").absolutePath)
        }
    }
}

/** 后端金标准 fixture 目录;可用 -PimCardFixtures=<dir> 覆盖。 */
val imCardFixtureDir: String =
    (findProperty("imCardFixtures") as String?)
        ?: rootProject
            .file("../we-meet/src/backend/core/tests/fixtures/im_cards")
            .absolutePath

dependencies {
    implementation(project(":core-design"))

    // Kotlin
    // Kotlin
    implementation(libs.kotlinx.coroutines.android)

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose (BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Networking — builds its own Retrofit from the host-provided OkHttp.
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // jusi-light-im SDK — composite-built from ../jusi-light-im/sdk/android.
    // The dependency-substitution rule in settings.gradle.kts maps this coordinate
    // to the local `:sdk-im` Gradle project.
    implementation(libs.jusi.lightim.sdk.im)

    // Shared org-directory data layer + ContactPicker (new-chat / add-members flows).
    implementation(project(":core-directory"))

    // Chat avatars + image message thumbnails.
    implementation(libs.coil.compose)

    // JVM 单测。org.json 必须显式引:MessageContentParser 用的是 android.jar 里
    // 的 org.json,而那份在单测 classpath 上全是 `throw RuntimeException("Stub!")`,
    // 不盖掉的话每个解析断言都会炸在 stub 上而不是在被测逻辑上。
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
