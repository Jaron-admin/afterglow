plugins {
    id("com.android.application")
}

android {
    namespace = "com.oldphone.ipcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oldphone.ipcam"
        minSdk = 19          // Android 4.4 KitKat
        targetSdk = 33
        versionCode = 10
        versionName = "1.4.3"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 自签名 APK：直接用 debug 签名即可，方便侧载安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
        }
    }
}

// 正式 APK 仍然不依赖第三方库或 AndroidX，体积和 minSdk 19 保持不变。
// Robolectric 与 Roborazzi 只用于界面截图测试。
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("androidx.compose.ui:ui-test:1.7.8")
}
