plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 设计系统的唯一真源:token(Color/Type/Dimens/Theme)+ 共享组件。
 *
 * 独立成模块是因为 feature-im / feature-assistant / core-directory 都需要它,
 * 而它们不能反过来依赖 app —— token 留在 app 里的话,这三个模块结构上就无法
 * 遵循同一套规范。见 docs/设计规范.md §1。
 *
 * **包名沿用 `com.we.meet.ui.theme` / `com.we.meet.ui.components`**(而不是跟着
 * 模块名改成 com.we.meet.design.*):Kotlin 不要求包名与目录一致,沿用旧包名意味着
 * app 里近百处 import 一行都不用动。这是有意为之,不是疏忽。
 *
 * 这个模块只放「所有 UI 都可能用到」的东西。业务组件不要往这塞 —— 它一旦变成
 * 大杂烩,每个模块都会因为无关改动而重新编译。
 */
android {
    namespace = "com.we.meet.design"
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose (BOM) —— 这个模块只依赖 Compose,不碰网络/协程/业务。
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
