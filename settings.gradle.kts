pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "WeMeetAndroid"
include(":app")
include(":feature-assistant")
include(":feature-im")

// jusi-light-im Android SDK lives in a sibling repo; pull it in via composite build
// and substitute the published coordinate to the local `:sdk-im` project so the
// feature module can declare `implementation("com.jusi.lightim:sdk-im")` as if it
// were a regular Maven artifact.
includeBuild("../../jusi-light-im/sdk/android") {
    dependencySubstitution {
        substitute(module("com.jusi.lightim:sdk-im")).using(project(":sdk-im"))
    }
}
