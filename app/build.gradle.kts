import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// release 签名读本地 keystore.properties（该文件不入库）。
// 缺文件时 storeFile 为空，构建 release 会在 validateSigningRelease 明确报错——
// 宁可报错，也不要静默产出未签名或用调试证书签的包。
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { this.load(it) }
}

android {
    namespace = "com.watchfridge.freezer"
    compileSdk = 35
    // 显式使用本机已安装的 Build Tools，避免 AGP 默认查找 34.0.0
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.watchfridge.freezer"
        // Shizuku 官方要求 minSdk >= 23；Wear OS 4 为 API 30+
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        // 生成 BuildConfig（用于区分调试构建）
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Wear OS
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.shizuku.aidl)

    // 关于页：zxing 本地生成二维码；remote-interactions 把打开链接这件事交给手机
    implementation(libs.zxing.core)
    implementation(libs.androidx.wear.remote.interactions)

    debugImplementation(libs.compose.ui.tooling)
}
