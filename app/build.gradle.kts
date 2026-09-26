import java.time.LocalDate

plugins {
    alias(libs.plugins.androidApplication)
    // AGP 9.0+ 内置 Kotlin 支持，不再需要 kotlinAndroid 插件
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// 手环小爱 AI 增强 —— LSPosed 模块（Modern Xposed API 102）
// 0.5.0-beta 起：基于 mi-band-ai（环上LLM）工程重构，身份（包名/签名/版本）换成用户的。
android {
    namespace = "com.zeroone01.xiaoai"
    // AGP 9.x 的 compileSdk 表达式 DSL。
    // libxposed 102 要求 compileSdk>=37，使用子系统 37.0（platforms/android-37.0）
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.zeroone01.xiaoai"
        minSdk = 26
        targetSdk = 35
        versionCode = 600
        versionName = "0.6.0-beta1"
        // 编译日期（首页「关于」展示）：随构建日期生成
        buildConfigField(
            "String",
            "BUILD_TIME",
            "\"${LocalDate.now()}\"",
        )
    }

    signingConfigs {
        create("xiaoai") {
            storeFile = file("xiaoai.jks")
            storePassword = "xiaoai123"
            keyAlias = "xiaoai"
            keyPassword = "xiaoai123"
        }
    }

    buildTypes {
        release {
            // 现代 Xposed API 模块建议开启混淆以隐藏实现细节
            // （proguard-rules.pro 保留 Xposed 入口 / Miuix / serialization）
            isMinifyEnabled = true
            isShrinkResources = false
            // 用户专属签名（xiaoai.jks，从 0.x 旧工程沿用）
            signingConfig = signingConfigs.getByName("xiaoai")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("xiaoai")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // ---- Modern Xposed API 102 ----
    // compileOnly：仅参与编译，不打包进 APK（由宿主框架提供）
    compileOnly(libs.libxposed.api)
    // implementation：打包进 APK，提供与框架通信的 service
    implementation(libs.libxposed.service)

    // ---- Miuix（HyperOS 设计语言）----
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)

    // ---- AndroidX Activity + Compose ----
    implementation(libs.androidx.activity.compose)

    // ---- Compose（显式声明，与 Miuix 同源同版本，避免冲突）----
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)

    // ---- Kotlin 序列化（用于解析/构造 WebSocket JSON 消息）----
    implementation(libs.kotlinx.serialization.json)
}
