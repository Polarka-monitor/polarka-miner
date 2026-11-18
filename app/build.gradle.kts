import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// RandomX comes from the TrueNorth sources of the release the network runs; -PrandomxDir=... names another copy.
val randomxDir = (project.findProperty("randomxDir") as String?)
    ?: (System.getProperty("user.home") + "/north-build/truenorth-src-v30.5.4/src/randomx")

android {
    namespace = "org.polarka.miner"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "org.polarka.miner"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DRANDOMX_DIR=$randomxDir", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DANDROID_STL=c++_static") }
        }
    }

    // The key that signs this app is its own, apart from the wallet's, and lives outside the project.
    val wantsRelease = gradle.startParameter.taskNames.any { it.contains("Release") }
    val signingFile = File(System.getProperty("user.home"), ".config/polarka-miner/signing.properties")
    val signing = Properties().apply { if (wantsRelease && signingFile.exists()) signingFile.inputStream().use { load(it) } }
    signingConfigs {
        if (wantsRelease && signingFile.exists()) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
