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
        versionCode = 5
        versionName = "0.5.0"
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
    // Веб-плеер для локального режима и страница P2P-ведущего
    sourceSets["main"].assets.srcDirs("../../relay/wavecast/static", "../../p2p/host")
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
