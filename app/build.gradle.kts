import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Month.Year.Release, where Release counts the releases within that month.
val appVersion = "10.2026.1"
val (versionMonth, versionYear, versionRelease) = appVersion.split(".").map(String::toInt)

// The release key lives outside the repo; builds without it fall back to the debug key.
val releaseKeys = File(System.getProperty("user.home"), ".config/statslop/keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "app.statslop"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.statslop"
        minSdk = 31
        targetSdk = 35
        // Must always increase, so it is built as YYYYMMRR (e.g. 20261001).
        versionCode = versionYear * 10_000 + versionMonth * 100 + versionRelease
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeys != null) create("release") {
            storeFile = file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
