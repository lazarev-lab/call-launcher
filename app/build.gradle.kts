import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.grandpalauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.grandpalauncher"
        minSdk = 23
        targetSdk = 35
        // Keep the code monotonic so this public 1.0 release updates earlier tablet builds.
        versionCode = 100
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

