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
// PostHog: leave WE_MEET_POSTHOG_KEY empty to keep analytics off. The
// Analytics singleton no-ops when the key is blank — useful for the
// aliyun-prod deployment which currently doesn't ship analytics, and
// for any dev build that doesn't want to pollute the project's events.
val posthogKey = cfg("WE_MEET_POSTHOG_KEY", "")
val posthogHost = cfg("WE_MEET_POSTHOG_HOST", "https://app.posthog.com")

android {
    namespace = "com.we.meet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.we.meet"
        // Bumped 24 → 29 for the realtime AI assistant (:feature-assistant),
        // which uses audio-routing / WebRTC APIs that require API 29+.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Surface config to BuildConfig.
        buildConfigField("String", "WE_MEET_BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "WE_MEET_KEYCLOAK_URL", "\"$keycloakUrl\"")
        buildConfigField("String", "WE_MEET_LIVEKIT_URL_OVERRIDE", "\"$livekitOverride\"")
        buildConfigField("String", "WE_MEET_POSTHOG_KEY", "\"$posthogKey\"")
        buildConfigField("String", "WE_MEET_POSTHOG_HOST", "\"$posthogHost\"")
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
    implementation(project(":feature-assistant"))

    // LiveKit
    implementation(libs.livekit.android)
    implementation(libs.livekit.android.compose.components)

    // Image loading
    implementation(libs.coil.compose)

    // QR scanning — self-contained CaptureActivity + ScanContract for the
    // Activity Result API. Pulled in as a direct coordinate rather than via
    // libs.versions.toml because it's the only ZXing-derived dep we use.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
