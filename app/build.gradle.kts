import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tierruos.stickers"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tierruos.stickers"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
        testInstrumentationRunner = "com.tierruos.stickers.security.SanitizerInstrumentation"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = false
        compose = false
        aidl = false
        renderScript = false
        shaders = false
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

tasks.register("verifyReleaseApkSize") {
    group = "verification"
    description = "Verifica que los cuatro APK de release pesen menos de 4 MB."
    dependsOn("assembleRelease")

    val releaseDirectory = layout.buildDirectory.dir("outputs/apk/release")
    doLast {
        val apks = releaseDirectory.get().asFile.listFiles()
            .orEmpty()
            .filter { it.extension == "apk" }
        check(apks.size == 4) { "Se esperaban tres APK por ABI y un APK universal." }
        apks.sortedBy { it.name }.forEach { apk ->
            logger.lifecycle("${apk.name}: ${apk.length()} bytes")
            check(apk.length() < 4_000_000L) {
                "${apk.name} supera el presupuesto estricto de 4 MB (4.000.000 bytes)."
            }
        }
    }
}
