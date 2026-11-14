pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.gradle.toml"))
        }
    }
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "id-pocket"
include(":app")
include(":models")
include(":domain")
include(":data:core")
include(":import:api")
include(":import:impl")
include(":account:api")
include(":account:impl")
include(":pin:api")
include(":pin:impl")
include(":max:api")
include(":max:impl")
include(":digitalid:api")
include(":digitalid:impl")
include(":maximport:api")
include(":maximport:impl")

project(":import").projectDir = file("data/repositories/import")
project(":import:api").projectDir = file("data/repositories/import/api")
project(":import:impl").projectDir = file("data/repositories/import/impl")

project(":account").projectDir = file("data/repositories/account")
project(":account:api").projectDir = file("data/repositories/account/api")
project(":account:impl").projectDir = file("data/repositories/account/impl")

project(":pin").projectDir = file("data/repositories/pin")
project(":pin:api").projectDir = file("data/repositories/pin/api")
project(":pin:impl").projectDir = file("data/repositories/pin/impl")

project(":max").projectDir = file("data/repositories/max")
project(":max:api").projectDir = file("data/repositories/max/api")
project(":max:impl").projectDir = file("data/repositories/max/impl")

project(":digitalid").projectDir = file("data/repositories/digitalid")
project(":digitalid:api").projectDir = file("data/repositories/digitalid/api")
project(":digitalid:impl").projectDir = file("data/repositories/digitalid/impl")

project(":maximport").projectDir = file("data/repositories/maximport")
project(":maximport:api").projectDir = file("data/repositories/maximport/api")
project(":maximport:impl").projectDir = file("data/repositories/maximport/impl")
