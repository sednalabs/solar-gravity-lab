pluginManagement {
    repositories {
        google()
        maven {
            name = "MavenCentralRepo1"
            url = uri("https://repo1.maven.org/maven2")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            name = "MavenCentralRepo1"
            url = uri("https://repo1.maven.org/maven2")
        }
        mavenCentral()
    }
}

// Canonical main is the Rust-owned platform line. The legacy Kotlin modules
// remain in the repository only as reference code and are intentionally not
// included in the root Gradle build.
//
// Use `./gradlew -p clients/android ...` for the forward Android shell.
rootProject.name = "SolarLabPlatform"
