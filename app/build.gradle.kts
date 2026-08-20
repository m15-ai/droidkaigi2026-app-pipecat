import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.m15.pica"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.m15.pica"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // Read local.properties safely
        val props = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }

        // Pica's only build-time config: the OpenClaw server's offer URL (the
        // /api/offer SDP endpoint — the conference demo target). No app key,
        // no JWT — signaling is "POST an SDP offer." See README.md for setup.
        // Further backends are added at runtime on the setup screen, not here.
        val openClawServerUrl = (props.getProperty("OPENCLAW_SERVER_URL")
            ?: System.getenv("OPENCLAW_SERVER_URL")
            ?: "").trim()

        if (openClawServerUrl.isEmpty()) throw GradleException("OPENCLAW_SERVER_URL missing")

        buildConfigField("String", "OPENCLAW_SERVER_URL", "\"$openClawServerUrl\"")

        // (optional) Room schema export for migrations
        javaCompileOptions {
            annotationProcessorOptions {
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }

    buildFeatures { compose = true
                    buildConfig = true
    }
}

dependencies {
    // --- Compose BOM (manages versions for all Compose artifacts) ---
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Material 3 (let BOM pick version)
    implementation("androidx.compose.material3:material3")

    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room (KSP!)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Material Components for XML theme parent
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Pipecat — official Android client SDK + SmallWebRTC transport.
    // Pulls the pipecat-client-android core and libwebrtc transitively.
    implementation(libs.pipecat.small.webrtc.transport)
}

// (optional) KSP arguments (Room schema export)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}