import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Read overridable config from local.properties first, then fall back to gradle.properties.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String = ""): String =
    localProps.getProperty(key)
        ?: (project.findProperty(key) as String?)
        ?: default

val baseUrl = cfg("WE_MEET_BASE_URL", "https://meet.we-meet.online")
val keycloakUrl = cfg("WE_MEET_KEYCLOAK_URL", "https://id.we-meet.online")
val livekitOverride = cfg("WE_MEET_LIVEKIT_URL_OVERRIDE", "")
val jusiImBaseUrl = cfg("JUSI_IM_BASE_URL", "https://im.we-meet.online")
val docsUrl = cfg("WE_MEET_DOCS_URL", "https://docs.we-meet.online")
val oidcClientId = cfg("WE_MEET_OIDC_CLIENT_ID", "app")
// WebView Keycloak login vs legacy native OTP — the rollback fuse (p3-docs-app.md D1).
val webLogin = cfg("WE_MEET_WEB_LOGIN", "true")
// PostHog: leave WE_MEET_POSTHOG_KEY empty to keep analytics off. The
// Analytics singleton no-ops when the key is blank — useful for the
// aliyun-prod deployment which currently doesn't ship analytics, and
// for any dev build that doesn't want to pollute the project's events.
val posthogKey = cfg("WE_MEET_POSTHOG_KEY", "")
val posthogHost = cfg("WE_MEET_POSTHOG_HOST", "https://app.posthog.com")
// Getui (个推) offline-push credentials. AppId/AppKey/AppSecret ship inside the
// APK by design (normal Getui practice); the sensitive MasterSecret lives
// server-side only and must never appear here.
val getuiAppId = cfg("GETUI_APPID")
val getuiAppKey = cfg("GETUI_APPKEY")
val getuiAppSecret = cfg("GETUI_APPSECRET")

android {
    namespace = "com.we.meet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.we.meet"
        // Bumped 24 → 29 for the realtime AI assistant (:feature-assistant),
        // which uses audio-routing / WebRTC APIs that require API 29+.
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        // Surface config to BuildConfig.
        buildConfigField("String", "WE_MEET_BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "WE_MEET_KEYCLOAK_URL", "\"$keycloakUrl\"")
        buildConfigField("String", "WE_MEET_LIVEKIT_URL_OVERRIDE", "\"$livekitOverride\"")
        buildConfigField("String", "WE_MEET_POSTHOG_KEY", "\"$posthogKey\"")
        buildConfigField("String", "WE_MEET_POSTHOG_HOST", "\"$posthogHost\"")
        buildConfigField("String", "JUSI_IM_BASE_URL", "\"$jusiImBaseUrl\"")
        buildConfigField("String", "WE_MEET_DOCS_URL", "\"$docsUrl\"")
        buildConfigField("String", "WE_MEET_OIDC_CLIENT_ID", "\"$oidcClientId\"")
        buildConfigField("boolean", "WE_MEET_WEB_LOGIN", webLogin)

        // Getui: the gtsdk AAR's manifest references ${GETUI_APPID} etc., and
        // our own <meta-data> entries in AndroidManifest.xml use the same
        // placeholders so credentials stay in gradle/local.properties.
        manifestPlaceholders["GETUI_APPID"] = getuiAppId
        manifestPlaceholders["GETUI_APPKEY"] = getuiAppKey
        manifestPlaceholders["GETUI_APPSECRET"] = getuiAppSecret
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    // Kotlin
    implementation(libs.kotlinx.coroutines.android)

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)

    // Compose (BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // CameraX (preview screen)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // Analytics (S4.4) — PostHog SDK. The Analytics singleton no-ops when
    // WE_MEET_POSTHOG_KEY is blank, so this dep is dormant on builds
    // without a key (most local + the current aliyun-prod deployment).
    implementation(libs.posthog.android)

    // AI assistant feature (Sprint 3) — realtime "打电话" call screen.
    // 设计系统(token + 共享组件)。api 而非 implementation:app 的公开
    // Composable 签名里会出现 Dimens/WeMeetTheme 这些类型。
    api(project(":core-design"))
    implementation(project(":feature-assistant"))

    // IM feature (P4) — chat list + 1:1/group messaging via jusi-light-im SDK.
    implementation(project(":feature-im"))

    // Shared org-directory data layer + ContactPicker (contacts tab, calendar attendees).
    implementation(project(":core-directory"))

    // LiveKit
    implementation(libs.livekit.android)
    implementation(libs.livekit.android.compose.components)

    // Image loading
    implementation(libs.coil.compose)

    // Getui (个推) unified push SDK — offline IM notifications (P0). Versions
    // pinned to what actually exists on mvn.getui.com (checked maven-metadata):
    // gtsdk latest 3.3.15.0, gtc (core runtime) latest 3.3.3.0.
    implementation("com.getui:gtsdk:3.3.15.0")
    implementation("com.getui:gtc:3.3.3.0")

    // QR scanning — self-contained CaptureActivity + ScanContract for the
    // Activity Result API. Pulled in as a direct coordinate rather than via
    // libs.versions.toml because it's the only ZXing-derived dep we use.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // JVM 单测。这个模块此前一个测试都没有 —— 加它是因为提醒角标的窗口口径
    // 必须和 Web / 服务端三处对齐,而「对齐」这种事只有测试守得住。
    // 先例:feature-im 的 build.gradle.kts。
    testImplementation(libs.junit)
}
