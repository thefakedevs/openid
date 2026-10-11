plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.lavafrai.openid.data.repositories.digitalid.impl"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(projects.digitalid.api)
    implementation(projects.data.core)
    implementation(libs.koin.core)
    implementation(libs.okhttp)
}
