import java.util.Properties

plugins {
    // 注意：AGP 9.0 起内置 Kotlin 支持，不再需要 `org.jetbrains.kotlin.android` 插件。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 从 local.properties 读取 SDK 路径（本地构建）或用环境变量（CI 构建）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.fengqi.xiaoai"
    compileSdk = 37
    // Android 37 是一个「次版本」SDK（package id: platforms;android-37.0），
    // AGP 9 起通过 compileSdkMinor 指定次版本号。
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.fengqi.xiaoai"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
    }

    // 使用 release 签名以便 LSPosed 正常加载（debug 签名亦可，这里统一用调试密钥）
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
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("xiaoai")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("xiaoai")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-jvm-default=no-compatibility")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // ---- Xposed API（compileOnly：运行期由 LSPosed 框架提供）----
    compileOnly(libs.xposed.api)

    // ---- Miuix UI（Compose Multiplatform）----
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)

    // ---- Compose ----
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)

    // ---- AndroidX 基础 ----
    implementation(libs.core.ktx)

    // ---- 存储 ----
    implementation(libs.datastore.preferences)

    // ---- 网络 / 序列化 / 协程 ----
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
