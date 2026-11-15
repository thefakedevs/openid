plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.lavafrai.openid.data.repositories.maximport.impl"
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
    implementation(projects.maximport.api)
    implementation(projects.data.core)
    implementation(projects.max.api)
    implementation(libs.koin.android)
    implementation(libs.xz)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
