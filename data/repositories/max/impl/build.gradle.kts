plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.lavafrai.openid.data.repositories.max.impl"
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
    implementation(projects.max.api)
    implementation(projects.data.core)
    implementation(libs.koin.core)
    implementation(libs.msgpack.core)
    implementation(libs.lz4.java)
}
