plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Compose Compiler plugin (Kotlin 2.0+ path; replaces the old kotlinCompilerExtensionVersion approach).
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.constellation.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.constellation.glass"
        // Android Go (YodaOS-Sprite base) ships API 32 in DVT firmware.
        // OnePlus 9 (phoneDebug) runs API 34. Floor is 28.
        minSdk = 28
        targetSdk = 32
        versionCode = 1
        versionName = "0.2.0-pivot-baremetal"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // ── Product flavors (v2.1 pivot) ───────────────────────────────────────
    // `glass`     → installs on the Rokid R08 itself (bare-metal Android Go);
    //               uses AudioRecord ChannelMask=0x6000FC, registers system
    //               key broadcasts in a Service, renders HUD via an
    //               always-on Activity. No CXR-L AAR.
    // `phoneDebug`→ installs on a regular Android phone for protocol /
    //               state-machine / WSS verification; uses standard mono
    //               AudioRecord and a SYSTEM_ALERT_WINDOW overlay; input
    //               is simulated via persistent-notification action buttons.
    flavorDimensions += "platform"
    productFlavors {
        create("glass") {
            dimension = "platform"
            buildConfigField("String", "PLATFORM", "\"glass\"")
            buildConfigField("boolean", "IS_GLASS", "true")
        }
        create("phoneDebug") {
            dimension = "platform"
            applicationIdSuffix = ".phonedebug"
            buildConfigField("String", "PLATFORM", "\"phoneDebug\"")
            buildConfigField("boolean", "IS_GLASS", "false")
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            buildConfigField("String", "WSS_URL", "\"wss://edge.example.com/ws/glass\"")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "WSS_URL", "\"wss://edge.example.com/ws/glass\"")
        }
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDirs("src/androidTest/kotlin")
    sourceSets.getByName("glass").kotlin.srcDirs("src/glass/kotlin")
    sourceSets.getByName("phoneDebug").kotlin.srcDirs("src/phoneDebug/kotlin")
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

    // Jetpack Compose (P1.6) — UI + Foundation only, no Material3.
    // Single source of truth via BOM. Shared by both glass + phoneDebug flavors
    // (HUD Composables live in app/src/main/.../hud/composables/).
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")

    // DataStore (P-app.A) — runtime-editable endpoint URL + future app prefs
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ⚠️ CXR-L AAR removed in v2.1 — we run as a bare-metal Android Go app
    //    directly on the glass, not as a phone-side bridge. See
    //    Constellation/docs/glass/GLASS-CLIENT-DESIGN.md v2.1.

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(composeBom)
}
