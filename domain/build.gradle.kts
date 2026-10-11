plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(projects.models)
    api(libs.kotlinx.coroutines.core)
    api(projects.`import`.api)
    api(projects.account.api)
    api(projects.pin.api)
    api(projects.max.api)
    api(projects.digitalid.api)
}
