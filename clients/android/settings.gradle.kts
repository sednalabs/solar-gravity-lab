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
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "SolarLabAndroidV2"
include(":app")
include(":core-math")
include(":core-model")
include(":core-simulation")
include(":render-core")
include(":feature-lab")

project(":core-math").projectDir = file("../../core-math")
project(":core-model").projectDir = file("../../core-model")
project(":core-simulation").projectDir = file("../../core-simulation")
project(":render-core").projectDir = file("../../render-core")
project(":feature-lab").projectDir = file("../../feature-lab")
