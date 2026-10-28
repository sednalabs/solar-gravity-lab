pluginManagement {
    // AGP 8.13 bundles an older R8; Kotlin 2.4 requires R8 9.1.29 or newer.
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.31")
        }
    }

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
    // UTP resolves its plugins in separate configurations from buildscript.
    // Apply constraints to the observed plugin modules, not application code.
    components {
        val utpPlugins = listOf(
            "com.android.tools.utp:android-test-plugin-host-additional-test-output",
            "com.android.tools.utp:android-test-plugin-host-apk-installer",
            "com.android.tools.utp:android-test-plugin-host-coverage",
            "com.android.tools.utp:android-test-plugin-host-device-info",
            "com.android.tools.utp:android-test-plugin-host-emulator-control",
            "com.android.tools.utp:android-test-plugin-host-logcat",
            "com.android.tools.utp:android-test-plugin-result-listener-gradle",
        )
        val nettyModules = listOf(
            "netty-buffer", "netty-codec", "netty-codec-http", "netty-codec-http2",
            "netty-codec-socks", "netty-common", "netty-handler", "netty-handler-proxy",
            "netty-resolver", "netty-transport", "netty-transport-native-unix-common",
        )
        utpPlugins.forEach { module ->
            withModule(module) {
                if (id.version == "31.13.2") {
                    allVariants {
                        withDependencyConstraints {
                            add("com.google.guava:guava:33.7.2-jre")
                            if (module.endsWith("host-emulator-control") || module.endsWith("result-listener-gradle")) {
                                nettyModules.forEach { add("io.netty:$it:4.1.137.Final") }
                            }
                        }
                    }
                }
            }
        }
        withModule("com.google.testing.platform:android-test-plugin") {
            if (id.version == "0.0.9-alpha03") {
                allVariants {
                    withDependencyConstraints {
                        add("com.google.guava:guava:33.7.2-jre")
                    }
                }
            }
        }
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
include(":android-vulkan-renderer")

project(":core-math").projectDir = file("../../core-math")
project(":core-model").projectDir = file("../../core-model")
project(":core-simulation").projectDir = file("../../core-simulation")
project(":render-core").projectDir = file("../../render-core")
project(":android-vulkan-renderer").projectDir = file("../../render/android-vulkan")
