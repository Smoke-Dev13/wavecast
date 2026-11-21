import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ключ подписи хранится только у владельца, в репозиторий не попадает.
// Берётся из android/keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
// или из переменных окружения WAVECAST_KEYSTORE, WAVECAST_STORE_PASSWORD, WAVECAST_KEY_ALIAS, WAVECAST_KEY_PASSWORD.
val signProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun sign(prop: String, env: String): String? = signProps.getProperty(prop) ?: System.getenv(env)
val storePath: String? = sign("storeFile", "WAVECAST_KEYSTORE")

android {
    namespace = "dev.smoke.wavecast"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.smoke.wavecast"
        minSdk = 29          // AudioPlaybackCapture появился в Android 10
        targetSdk = 34
        versionCode = 31
        versionName = "0.17.0"
    }

    signingConfigs {
        storePath?.let { path -> create("release") {
            storeFile = rootProject.file(path)
            storePassword = sign("storePassword", "WAVECAST_STORE_PASSWORD")
            keyAlias = sign("keyAlias", "WAVECAST_KEY_ALIAS") ?: "wavecast"
            keyPassword = sign("keyPassword", "WAVECAST_KEY_PASSWORD") ?: storePassword
        } }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // без ключа собирается неподписанный APK — поставить его на телефон нельзя
            if (storePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Сборка без Google Play: строгие проверки lint для релиза не нужны и не должны валить сборку
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
