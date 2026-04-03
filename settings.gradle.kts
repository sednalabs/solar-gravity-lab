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

rootProject.name = "SolarLab"

include(
    ":app",
    ":core-math",
    ":core-model",
    ":core-simulation",
    ":feature-lab",
    ":render-core",
)
