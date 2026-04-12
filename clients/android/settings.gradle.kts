import org.gradle.caching.http.HttpBuildCache

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

val isCi = System.getenv("CI").equals("true", ignoreCase = true)
val remoteCacheUrl = System.getenv("GRADLE_REMOTE_CACHE_URL")?.takeIf(String::isNotBlank)
val remoteCacheUsername = System.getenv("GRADLE_REMOTE_CACHE_USERNAME")?.takeIf(String::isNotBlank)
val remoteCachePassword = System.getenv("GRADLE_REMOTE_CACHE_PASSWORD")?.takeIf(String::isNotBlank)
val remoteCachePush = System.getenv("GRADLE_REMOTE_CACHE_PUSH").equals("true", ignoreCase = true)
val remoteCacheConfigured = remoteCacheUrl != null && remoteCacheUsername != null && remoteCachePassword != null

buildCache {
    local {
        // Prefer the shared remote cache on CI when it is configured.
        isEnabled = !(isCi && remoteCacheConfigured)
    }
    if (remoteCacheConfigured) {
        val cacheUrl = remoteCacheUrl!!
        val cacheUsername = remoteCacheUsername!!
        val cachePassword = remoteCachePassword!!
        remote<HttpBuildCache> {
            url = uri(cacheUrl)
            credentials {
                username = cacheUsername
                password = cachePassword
            }
            isPush = remoteCachePush
            isUseExpectContinue = true
            isAllowUntrustedServer = false
        }
    }
}
