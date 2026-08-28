plugins {
    id("com.android.application")
}

android {
    namespace = "dev.ainasnow.specialcare"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ainasnow.specialcare"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Xposed/LSPosed supplies this API at runtime; do not package it into the APK.
    compileOnly("de.robv.android.xposed:api:82")
}
