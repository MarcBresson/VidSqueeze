plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.squeeze.jxl"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 33
        ndk {
            // Matches the app's ABI splits (arm64 devices + x86_64 emulator).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
