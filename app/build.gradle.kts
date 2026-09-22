plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.davidlevi.radioabba"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.davidlevi.radioabba"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // A fixed, committed keystore (not the machine-local ~/.android/debug.keystore,
    // which GitHub Actions regenerates with a new random key on every run). Without
    // this, every CI build would produce a differently-signed APK, and installing
    // an update over the previous one would fail with a signature mismatch —
    // forcing a manual uninstall each time. This keystore only ever signs debug
    // builds of a hobby app; it is not a secret worth protecting.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug-keystore.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
}
