plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.constellation.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.constellation.glass"
        minSdk = 28              // CXR-L AAR floor (1.0.1)
        targetSdk = 32           // Android 12L — R08 ships on API 32
        versionCode = 1
        versionName = "0.1.0-phase3b.2"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            buildConfigField("String", "WSS_URL", "\"wss://edge.example.com/ws/glass\"")
            // When true: skip Rokid CXRLink init + token check, run WSS +
            // StateMachine "headless" (HUD calls log to Timber instead of
            // hitting the SDK). Lets us validate the network path on any
            // Android device before the actual Rokid Glass is in hand.
            buildConfigField("boolean", "DEV_HEADLESS", "true")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "WSS_URL", "\"wss://edge.example.com/ws/glass\"")
            buildConfigField("boolean", "DEV_HEADLESS", "false")
        }
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDirs("src/androidTest/kotlin")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OkHttp WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON serde
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ── Rokid SDKs ─────────────────────────────────────────────────────
    // CXR-L: glasses-native HUD + audio rendering via system Sprite service.
    implementation("com.rokid.cxr:client-l:1.0.1")
    // InstructSdk: offline voice commands (registered in Phase 3b.3).
    // implementation("com.rokid.ai.glass:instructsdk:1.1.4")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
