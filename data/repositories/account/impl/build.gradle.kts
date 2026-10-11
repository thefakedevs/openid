plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.lavafrai.openid.data.repositories.account.impl"
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
    implementation(projects.account.api)
    implementation(projects.data.core)
    implementation(libs.koin.core)
}
