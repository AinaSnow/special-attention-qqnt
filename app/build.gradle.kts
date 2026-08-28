plugins {
    id("com.android.application")
}

val ciVersionCode = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .orNull
    ?.toIntOrNull()
val explicitVersionCode = providers.gradleProperty("versionCode")
    .orNull
    ?.toIntOrNull()
val buildVersionCode = explicitVersionCode ?: ciVersionCode ?: 1
val buildVersionName = providers.gradleProperty("versionName")
    .orNull
    ?: "0.1.$buildVersionCode"

val signingStoreFile = providers.environmentVariable("SIGNING_STORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
val hasCiSigning = !signingStoreFile.isNullOrBlank() &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank()

android {
    namespace = "dev.ainasnow.specialcare"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ainasnow.specialcare"
        minSdk = 26
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = buildVersionName
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                storeFile = rootProject.file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            isMinifyEnabled = false
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
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
