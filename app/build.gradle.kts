plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.capmo.insta360spike"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "de.capmo.insta360spike"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        multiDexEnabled = true
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        // Exclusions mirror the packaging block in Insta360 SDK demo v1.10.1.
        // The *Skel.so files are Hexagon DSP skeletons for specific Qualcomm chip
        // generations — huge and on-device-only. The SDK dynamically selects one
        // at runtime and we don't use the AI-accelerated paths for basic 360
        // playback. When bumping sdkmedia / sdkcamera, diff the new demo's
        // packaging block against this list before trimming further.
        jniLibs {
            // Required for 16KB-page Android devices (Android 15+). Stores .so
            // files uncompressed and page-aligned inside the APK so the dynamic
            // linker can mmap them directly.
            useLegacyPackaging = false
            excludes += listOf(
                "lib/arm64-v8a/libSnpeHtpV68Skel.so",
                "lib/arm64-v8a/libSnpeHtpV69Skel.so",
                "lib/arm64-v8a/libSnpeHtpV73Skel.so",
                "lib/arm64-v8a/libSnpeHtpV75Skel.so",
                "lib/arm64-v8a/libSnpeHtpV79Skel.so",
                "lib/arm64-v8a/libcalculator_skel.so",
                // We only ship arm64-v8a.
                "lib/x86/*",
                "lib/x86_64/*",
                "lib/armeabi-v7a/*",
            )
        }
        resources {
            excludes += listOf("META-INF/rxjava.properties")
            pickFirsts += listOf("lib/arm64-v8a/libc++_shared.so")
        }
    }

    buildTypes {
        getByName("release") {
            // Unminified so size numbers reflect the SDK, not R8 stripping.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // Already-compressed media — don't waste build cycles trying to deflate them.
    androidResources {
        noCompress += listOf("insv", "insp", "mp4")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.insta.media)
    // Needed at runtime for libarffmpeg.so and its siblings — apparently some of
    // the native libs used by the rendering path ship via the camera AAR tree,
    // not the media tree. We don't USE InstaCameraSDK but we link against its libs.
    implementation(libs.insta.camera)
}
