// settings.gradle.kts
// -------------------------------------------------------------------
// This is the very first file Gradle reads. It tells Gradle:
//   1. Where to download plugins and libraries from (repositories).
//   2. The human-readable name of the whole project.
//   3. Which sub-modules (here just ":app") belong to the build.
// -------------------------------------------------------------------

pluginManagement {
    repositories {
        google()          // Google's Android plugins live here
        mavenCentral()    // Most third-party Kotlin/Java libraries
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Force every module to use ONLY the repositories listed here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Odysseus"
include(":app")
