plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zlang.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zlang.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // 添加 NDK 配置
        externalNativeBuild {
            cmake {
                // 可选：设置 CMake 参数
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        // 指定支持的 CPU 架构
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 配置 CMake 版本
    externalNativeBuild {
        cmake {
            // 指定 CMakeLists.txt 路径
            path = file("CMakeLists.txt")
            version = "3.18.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation("com.google.code.gson:gson:2.7")
    // Media3 依赖
    implementation("androidx.media3:media3-exoplayer:1.3.0")
    implementation("androidx.media3:media3-ui:1.3.0")
    implementation("androidx.media3:media3-common:1.3.0")
    implementation("androidx.media3:media3-session:1.3.0")
    implementation(libs.androidx.recyclerview)
    implementation(libs.recyclerview)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(project(":RemoteLogcatViewer:remotelogcat"))

    // Glide 核心库
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.squareup.okio:okio:3.6.0")

}