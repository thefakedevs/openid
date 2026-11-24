plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun versionCodeOf(version: String): Int {
    require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(version)) {
        "Version must look like 1.2.3: $version"
    }
    val parts = version.split(".").map { it.toLongOrNull() ?: error("Version component is too large") }
    require(parts[0] <= 2100 && parts[1] < 1000 && parts[2] < 1000) {
        "Version components exceed the Android versionCode range"
    }
    val code = parts[0] * 1_000_000 + parts[1] * 1000 + parts[2]
    require(code in 1..2_100_000_000) { "Android versionCode must be between 1 and 2100000000" }
    return code.toInt()
}

val sourceVersion = providers.gradleProperty("openid.version").get()
val buildVersion = providers.environmentVariable("OPENID_BUILD_VERSION").orElse(sourceVersion).get()
val buildVersionCode = versionCodeOf(buildVersion)
val expectedSourceVersionCode = versionCodeOf(sourceVersion).toString()
val configuredSourceVersionCode = providers.gradleProperty("openid.versionCode").get()

val checkVersionCode = tasks.register("checkVersionCode") {
    doLast {
        check(configuredSourceVersionCode == expectedSourceVersionCode) {
            "Set openid.versionCode=$expectedSourceVersionCode in gradle.properties for openid.version=$sourceVersion"
        }
    }
}
tasks.named("preBuild") { dependsOn(checkVersionCode) }

val signingVariables = listOf(
    "ANDROID_KEYSTORE",
    "ANDROID_KEYSTORE_KEY",
    "ANDROID_KEYSTORE_KEY_PASSWORD",
    "ANDROID_KEYSTORE_PASSWORD",
)
val releaseSigning = signingVariables.associateWith { providers.environmentVariable(it).orNull }
require(releaseSigning.values.all { it.isNullOrBlank() } || releaseSigning.values.none { it.isNullOrBlank() }) {
    "Release signing requires all of: ${signingVariables.joinToString()}"
}
val releaseKeystore = releaseSigning["ANDROID_KEYSTORE"]?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }

android {
    namespace = "ru.lavafrai.openid"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "ru.lavafrai.openid"
        minSdk = 26
        targetSdk = 37
        versionCode = buildVersionCode
        versionName = buildVersion
    }

    buildTypes {
        debug {
            // Installs next to the release build instead of replacing it
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "OpenID Dev"
            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            manifestPlaceholders["appLabel"] = "OpenID"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                keyAlias = releaseSigning.getValue("ANDROID_KEYSTORE_KEY")
                keyPassword = releaseSigning.getValue("ANDROID_KEYSTORE_KEY_PASSWORD")
                storePassword = releaseSigning.getValue("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        jniLibs {
            // AndroidX ships this library already stripped for every ABI.
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
            // libdfroot.so and libbootstrap.so are executed as standalone binaries (not loaded
            // via System.loadLibrary), so they must be extracted to nativeLibraryDir on install.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(projects.models)
    implementation(projects.domain)
    implementation(projects.data.core)
    implementation(projects.`import`.impl)
    implementation(projects.account.impl)
    implementation(projects.pin.impl)
    implementation(projects.max.impl)
    implementation(projects.digitalid.impl)
    implementation(projects.maximport.api)
    implementation(projects.maximport.impl)
    implementation(projects.dfroot)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.koin.android)
    implementation(libs.qrose)
    implementation(libs.materialkolor)
    implementation(libs.material.symbols.rounded)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
