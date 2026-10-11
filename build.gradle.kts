buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

spotless {
    kotlin {
        target("app/src/**/*.kt", "data/**/src/**/*.kt", "domain/src/**/*.kt", "models/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig").path)
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "data/**/*.gradle.kts", "domain/*.gradle.kts", "models/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig").path)
    }
}

tasks.register("ktlintCheck") {
    group = "verification"
    description = "Checks Kotlin and Gradle Kotlin DSL style with ktlint."
    dependsOn(tasks.named("spotlessCheck"))
}

tasks.register("ktlintFormat") {
    group = "formatting"
    description = "Formats Kotlin and Gradle Kotlin DSL sources with ktlint."
    dependsOn(tasks.named("spotlessApply"))
}
