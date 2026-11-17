plugins {
    alias(libs.plugins.android.library)
}

android {
    // The vendored DFRoot sources reference their own module BuildConfig; upstream keeps this namespace.
    namespace = "df.root"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Matches the app; the exploit itself requires API 28+ and is guarded at the call site.
        minSdk = 26
        buildConfigField("String", "VERSION_NAME", "\"4.7-dfroot\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
