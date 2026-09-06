plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.we.meet.feature.docs"
    compileSdk = 34

    defaultConfig {
        // Match the host app and the other feature modules.
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
    implementation(project(":core-design"))
    // DocsDeps extends DirectoryDeps (authedOkHttp + baseUrl come from the host contract).
    implementation(project(":core-directory"))

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

    // Networking — builds its own Retrofit on top of the host-provided
    // authenticated OkHttp (meet ticket endpoint) plus a docs-session OkHttp
    // (docs REST with docs_sessionid cookie + CSRF header).
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // docs_sessionid / csrftoken persistence — same crypto store as TokenStore.
    implementation(libs.androidx.security.crypto)

    // Doc images (M2 read mode) — AsyncImage.
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
