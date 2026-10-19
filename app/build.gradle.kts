import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 敏感凭据从 local.properties 读取（该文件已被 .gitignore 忽略，不会上传 GitHub）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.see"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.see"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 讯飞 AIKit 方法数较多，开启 multidex
        multiDexEnabled = true

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 凭据注入 BuildConfig（值来自 local.properties，不写死在源码里）
        buildConfigField("String", "SILICONFLOW_API_KEY", "\"${localProps.getProperty("SILICONFLOW_API_KEY", "")}\"")
        buildConfigField("String", "IFLYTEK_APP_ID", "\"${localProps.getProperty("IFLYTEK_APP_ID", "")}\"")
        buildConfigField("String", "IFLYTEK_API_KEY", "\"${localProps.getProperty("IFLYTEK_API_KEY", "")}\"")
        buildConfigField("String", "IFLYTEK_API_SECRET", "\"${localProps.getProperty("IFLYTEK_API_SECRET", "")}\"")
        buildConfigField("String", "IFLYTEK_ABILITY_ID", "\"${localProps.getProperty("IFLYTEK_ABILITY_ID", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // CameraX
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    // TFLite 本地物体检测（模型随 APK 打包，离线可用，不依赖 Google Play 服务）
    // 说明：2.14.0 的 tensorflow-lite 与 tensorflow-lite-api 命名空间相同，
    // 在 AGP 9 上会触发 "Namespace is used in multiple modules" 报错；
    // 2.16.1 已把 API 包命名空间改为 org.tensorflow.lite.api，故使用 2.16.1。
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // 讯飞 AIKit 离线语音识别（本地 AAR）
    implementation(files("libs/AIKit.aar"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}