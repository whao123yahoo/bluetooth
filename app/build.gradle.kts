plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // id("org.jetbrains.kotlin.plugin.compose")  // 如果不用 Compose 就注释掉
    id("dev.rikka.tools.autoresconfig")
    id("net.ankio.xposed") version "1.0.1"
}

android {
    namespace = "net.ankio.bluetooth"
    compileSdk = 34  // 使用更稳定的版本

    defaultConfig {
        applicationId = "net.ankio.bluetooth"
        minSdk = 30
        targetSdk = 33
        versionCode = 20
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // 只保留 arm 架构，移除 x86
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        // 限制资源语言
        resConfigs("zh", "en")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("debug") {
            // Debug 也开启混淆减少体积
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17  // 使用 Java 17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    // 如果不用 Compose，移除这个
    buildFeatures {
        // compose = true
        viewBinding = true  // 使用传统 View 绑定
    }
    
    autoResConfig {
        generateClass.set(true)
        generateRes.set(false)
        generatedClassFullName.set("net.ankio.utils.LangList")
        generatedArrayFirstItem.set("SYSTEM")
    }
    
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/ASL2.0",
                "META-INF/gson/**",  // 简化排除
                "**/*.kotlin_module"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 核心依赖 - 最小化
    implementation("com.github.AnkioTomas.XposedLib:lib:1.0.1")
    compileOnly("de.robv.android.xposed:api:82")
    
    // 如果不需要 UI，这些都可以移除
    // 如果需要简单 UI，使用传统方式
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // 工具库 - 按需保留
    implementation("com.google.code.gson:gson:2.10.1")  // 如果不需要 JSON 解析可以移除
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")  // 如果不需要协程可以移除
    
    // 测试依赖
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    
    // 移除以下依赖（如果不必要）
    // implementation("com.github.AnkioTomas:theme:1.1.5")
    // implementation("com.github.AnkioTomas:webdav:1.0.4")
}

xposed {
    entryClass.set("net.ankio.bluetooth.hook.BluetoothXposedEntry")
    moduleDescription.set("A tool that can debug Bluetooth / 一个可以调试蓝牙的工具")
    minXposedVersion.set(93)
    scope("com.android.bluetooth", "net.ankio.bluetooth")
}