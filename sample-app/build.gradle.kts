import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pixellab.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pixellab.app"
        minSdk = 26
        // targetSdk 36（Android 16）：sample-app 是演示 App，跟随当前 Play
        // 政策基线；库模块本身不携带 targetSdk，宿主自行决策（宿主因
        // SELinux 红线钉 28，不受本字段影响）。
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    // 与 pixel-core 同钉：NDK r30 + CMake 3.31.6（runner 预装，CI 免下载）。
    ndkVersion = "30.0.16248370"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 2.3 时代 kotlinOptions DSL 已废弃，迁移到 compilerOptions DSL。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":pixel-core"))
    implementation(project(":pixel-ui-compose"))
    implementation(project(":pixel-mcp"))
    implementation(project.dependencies.platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
}
