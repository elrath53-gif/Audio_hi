plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.estudioeq.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.estudiomaster.hires"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.0"
    }

    // Almacena el 100% de los datos acústicos WAV 32-bit Float, fuentes TTF, iconos y motor HTML/CSS/JS
    // en modo STORE (0% compresión DEFLATE) para acceso directo mmap zero-copy desde la memoria flash.
    androidResources {
        noCompress += listOf(
            "wav", "pcm", "raw", "bin",
            "ttf", "otf", "woff", "woff2",
            "html", "js", "css", "json",
            "png", "webp", "svg"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Signed with debug config by default so release/debug APKs install directly on phone
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
