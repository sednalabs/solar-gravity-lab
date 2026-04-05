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

// Canonical main is the Rust-owned platform line. The legacy Kotlin modules
// remain in the repository only as reference code and are intentionally not
// included in the root Gradle build.
//
// Use `./gradlew -p clients/android ...` for the forward Android shell.
rootProject.name = "SolarLabPlatform"
