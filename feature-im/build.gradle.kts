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
}

dependencies {
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
    implementation("com.jusi.lightim:sdk-im")

    // Shared org-directory data layer + ContactPicker (new-chat / add-members flows).
    implementation(project(":core-directory"))

    // Chat avatars + image message thumbnails.
    implementation(libs.coil.compose)
}
