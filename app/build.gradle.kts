import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

// 从 local.properties 读取 SDK 路径（本地构建）或用环境变量（CI 构建）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.zeroone01.xiaoai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zeroone01.xiaoai"
        minSdk = 24
        targetSdk = 35
        versionCode = 16
        versionName = "0.1.0-beta16"

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    ndkVersion = "27.0.12077973"
    buildToolsVersion = "35.0.0"

    externalNativeBuild {
        cmake {
            path = file("cpp/CMakeLists.txt")
            version = "3.28.3"
        }
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
        }
    }

    buildFeatures {
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
    compileOnly(libs.xposed.api)

    implementation(libs.core.ktx)
    implementation(libs.activity)
    implementation(libs.activity.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
