// Root build file. Per-module configuration lives in each module's build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // kotlin-serialization 插件已随 2026-11 工具链升级移除：仓库内无任何模块
    // 使用 kotlinx-serialization（pixel-mcp 的 JSON 为手写实现），目录中的
    // 旧 pin 见 git 历史。
}
