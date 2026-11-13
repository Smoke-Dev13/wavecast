plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.smoke.wavecast"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.smoke.wavecast"
        minSdk = 29          // AudioPlaybackCapture появился в Android 10
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // Фиксированный публичный ключ, чтобы новые сборки ставились поверх старых.
        create("oss") {
            storeFile = file("wavecast.keystore")
            storePassword = "wavecast"
            keyAlias = "wavecast"
            keyPassword = "wavecast"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("oss")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Тот же веб-плеер, что и у relay-сервера
    sourceSets["main"].assets.srcDirs("../../relay/wavecast/static")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
