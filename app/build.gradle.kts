plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mioassistente"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mioassistente"
        minSdk = 26
        targetSdk = 35
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.$run"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }

    // I file del modello Vosk non vanno compressi nell'APK
    androidResources { noCompress += listOf("mdl", "fst", "conf", "int", "mat") }
}

dependencies {
    // Riconoscimento vocale offline, open source, nessun account
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
