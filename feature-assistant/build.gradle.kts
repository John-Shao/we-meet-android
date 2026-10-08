plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Shared by AOQ and WebRTC; override with -P when building either variant.
val aiCallPreviewFps = providers.gradleProperty("AI_CALL_LOCAL_PREVIEW_FPS").orElse("15").get().toInt()
val aiCallUploadFps = providers.gradleProperty("AI_CALL_MODEL_UPLOAD_FPS").orElse("2").get().toInt()
require(aiCallPreviewFps in 1..30) { "AI_CALL_LOCAL_PREVIEW_FPS must be between 1 and 30" }
require(aiCallUploadFps in 1..aiCallPreviewFps) {
    "AI_CALL_MODEL_UPLOAD_FPS must be between 1 and AI_CALL_LOCAL_PREVIEW_FPS"
}

android {
    namespace = "com.we.meet.feature.assistant"
    compileSdk = 34

    defaultConfig {
        // Sprint 3: the realtime AI call uses APIs (audio routing / WebRTC)
        // that the assistant app shipped at minSdk 29; the host app is bumped
        // to match.
        minSdk = 29
        buildConfigField("int", "AI_CALL_LOCAL_PREVIEW_FPS", aiCallPreviewFps.toString())
        buildConfigField("int", "AI_CALL_MODEL_UPLOAD_FPS", aiCallUploadFps.toString())
        // Independent of camera-control acceptance; applies to both build variants.
        buildConfigField("boolean", "AI_CALL_VOICE_HANGUP",
            providers.gradleProperty("AI_CALL_VOICE_HANGUP").orElse("true").get().toBooleanStrict().toString())
        // Keep the validated media default; allow internal AEC comparisons in both variants.
        // VoIP selects the SDK hardware-AEC path and Android call volume.
        buildConfigField("boolean", "AOQ_MEDIA_PLAYBACK",
            providers.gradleProperty("AOQ_MEDIA_PLAYBACK").orElse("true").get().toBooleanStrict().toString())
        // Enable release only after both transports and physical-device acceptance.
        buildConfigField("boolean", "AI_CALL_CAMERA_VOICE_CONTROL", "false")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "AI_CALL_CAMERA_VOICE_CONTROL",
                providers.gradleProperty("AI_CALL_CAMERA_VOICE_CONTROL").orElse("true").get().toBooleanStrict().toString())
        }
        release {
            // Explicit opt-in for internal release acceptance; production default stays off.
            buildConfigField("boolean", "AI_CALL_CAMERA_VOICE_CONTROL",
                providers.gradleProperty("AI_CALL_CAMERA_VOICE_CONTROL_RELEASE").orElse("false").get().toBooleanStrict().toString())
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
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
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

    // LiveKit realtime (raw SDK; no compose-components here)
    implementation(libs.livekit.android)
    // The host APK packages the local AAR; libraries cannot embed another local AAR.
    compileOnly(files("libs/AoqClientSdk-release.aar"))
    testImplementation(files("libs/AoqClientSdk-release.aar"))
}
