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
        versionCode = 8
        versionName = "1.4.1"
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
}

// 本项目刻意不引入任何第三方 / AndroidX 依赖：
// 1) 让 APK 体积保持在 ~100KB 级别，老机器安装和启动更快；
// 2) 让 minSdk 19 不受 AndroidX 最低版本限制。
dependencies {
}
