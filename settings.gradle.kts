pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "open-bot-avatar-kt"
include(":open-bot-avatar-core", ":open-bot-avatar-compose")
project(":open-bot-avatar-core").projectDir = file("core")
project(":open-bot-avatar-compose").projectDir = file("compose")
