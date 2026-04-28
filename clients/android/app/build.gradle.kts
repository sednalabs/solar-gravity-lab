import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import kotlin.math.max
import javax.inject.Inject

@CacheableTask
abstract class BuildSolarlabNativeTask : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    @get:Input
    abstract val workspaceRootPath: Property<String>

    @get:Input
    abstract val ffiCrateRelativePath: Property<String>

    @get:Input
    abstract val androidRootLocalPropertiesPath: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustWorkspaceInputs: ConfigurableFileCollection

    @get:Input
    abstract val rustcVersion: Property<String>

    @get:Input
    abstract val cargoNdkVersion: Property<String>

    @get:Input
    abstract val ndkIdentity: Property<String>

    @get:OutputDirectory
    abstract val generatedJniLibsDir: DirectoryProperty

    @TaskAction
    fun buildNativeLibraries() {
        val workspaceRoot = File(workspaceRootPath.get())
        val ffiCrateDir = workspaceRoot.resolve(ffiCrateRelativePath.get())
        requireDirectoryExists(workspaceRoot, "Workspace root")
        requireDirectoryExists(ffiCrateDir, "FFI crate")

        val ndkDir = resolveNdkDirectory()
            ?: throw GradleException(
                "Android NDK not found. Set ANDROID_NDK_HOME/ANDROID_NDK_ROOT/NDK_HOME " +
                    "or install an NDK under ANDROID_SDK_ROOT/ANDROID_HOME."
            )

        ensureCommandWorks(
            command = listOf("cargo", "ndk", "--version"),
            failureHint = "Install cargo-ndk with `cargo install cargo-ndk`."
        )

        val requiredRustTargets = listOf("aarch64-linux-android", "x86_64-linux-android")
        val installedRustTargets = queryInstalledRustTargets()
        val missingTargets = requiredRustTargets.filterNot(installedRustTargets::contains)
        if (missingTargets.isNotEmpty()) {
            throw GradleException(
                "Missing Rust Android targets: ${missingTargets.joinToString(", ")}. " +
                    "Install with `rustup target add ${missingTargets.joinToString(" ")}`."
            )
        }

        val outputDir = generatedJniLibsDir.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        execOps.exec {
            workingDir = workspaceRoot
            environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
            environment("ANDROID_NDK_ROOT", ndkDir.absolutePath)
            commandLine(
                "cargo",
                "ndk",
                "-t",
                "arm64-v8a",
                "-t",
                "x86_64",
                "-o",
                outputDir.absolutePath,
                "build",
                "-p",
                "solarlab-ffi",
                "--release",
            )
        }

        val expectedLibraries = listOf(
            outputDir.resolve("arm64-v8a/libsolarlab_v2.so"),
            outputDir.resolve("x86_64/libsolarlab_v2.so"),
        )
        val missingLibraries = expectedLibraries.filterNot(File::isFile)
        if (missingLibraries.isNotEmpty()) {
            throw GradleException(
                "Native build completed but expected libraries are missing: " +
                    missingLibraries.joinToString { it.absolutePath }
            )
        }
    }

    private fun requireDirectoryExists(path: File, label: String) {
        if (!path.isDirectory) {
            throw GradleException("$label directory does not exist: ${path.absolutePath}")
        }
    }

    private fun resolveNdkDirectory(): File? {
        val directEnvPaths = listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME")
            .mapNotNull { System.getenv(it) }
            .map(::File)
            .firstOrNull(File::isDirectory)
        if (directEnvPaths != null) {
            return directEnvPaths
        }

        val sdkRoot = resolveSdkRootDirectory() ?: return null

        val ndkRoot = sdkRoot.resolve("ndk")
        if (!ndkRoot.isDirectory) {
            return null
        }

        return ndkRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.maxWithOrNull(::compareNdkDirectories)
    }

    private fun resolveSdkRootDirectory(): File? {
        val envSdkRoot = listOf("ANDROID_SDK_ROOT", "ANDROID_HOME")
            .mapNotNull { System.getenv(it) }
            .map(::File)
            .firstOrNull(File::isDirectory)
        if (envSdkRoot != null) {
            return envSdkRoot
        }

        val candidateLocalPropertiesFiles = buildList {
            add(File(androidRootLocalPropertiesPath.get()))
            val workspaceRootFile = workspaceRootPath.orNull?.let(::File)
            if (workspaceRootFile != null) {
                add(workspaceRootFile.resolve("local.properties"))
            }
        }

        candidateLocalPropertiesFiles.forEach { localPropertiesFile ->
            if (!localPropertiesFile.isFile) {
                return@forEach
            }
            val properties = Properties()
            localPropertiesFile.inputStream().use(properties::load)
            val sdkDir = properties.getProperty("sdk.dir")?.takeIf(String::isNotBlank) ?: return@forEach
            val sdkRoot = File(sdkDir)
            if (sdkRoot.isDirectory) {
                return sdkRoot
            }
        }

        return null
    }

    private fun ensureCommandWorks(command: List<String>, failureHint: String) {
        val stderr = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(command)
            isIgnoreExitValue = true
            errorOutput = stderr
        }
        if (result.exitValue != 0) {
            val detail = stderr.toString().trim().ifBlank { "command failed: ${command.joinToString(" ")}" }
            throw GradleException("$detail\n$failureHint")
        }
    }

    private fun queryInstalledRustTargets(): Set<String> {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine("rustup", "target", "list", "--installed")
            isIgnoreExitValue = true
            standardOutput = stdout
            errorOutput = stderr
        }
        if (result.exitValue != 0) {
            val detail = stderr.toString().trim().ifBlank { "rustup target list --installed failed" }
            throw GradleException("$detail\nInstall rustup from https://rustup.rs/ and retry.")
        }

        return stdout.toString()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
    }

    private fun compareNdkDirectories(left: File, right: File): Int {
        return compareVersionNames(left.name, right.name)
    }

    private fun compareVersionNames(left: String, right: String): Int {
        val leftParts = left.split(Regex("[^A-Za-z0-9]+")).filter(String::isNotEmpty)
        val rightParts = right.split(Regex("[^A-Za-z0-9]+")).filter(String::isNotEmpty)
        val maxParts = max(leftParts.size, rightParts.size)

        for (index in 0 until maxParts) {
            val leftPart = leftParts.getOrNull(index) ?: return -1
            val rightPart = rightParts.getOrNull(index) ?: return 1

            val order = compareVersionPart(leftPart, rightPart)
            if (order != 0) {
                return order
            }
        }

        return left.compareTo(right)
    }

    private fun compareVersionPart(left: String, right: String): Int {
        val leftNumeric = left.toLongOrNull()
        val rightNumeric = right.toLongOrNull()

        return when {
            leftNumeric != null && rightNumeric != null -> leftNumeric.compareTo(rightNumeric)
            leftNumeric != null || rightNumeric != null -> left.compareTo(right)
            else -> left.compareTo(right)
        }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun Project.stringPropertyOrEnv(propertyName: String, envName: String): String? {
    val propertyValue = findProperty(propertyName)?.toString()?.trim().orEmpty()
    if (propertyValue.isNotEmpty()) {
        return propertyValue
    }

    val envValue = System.getenv(envName)?.trim().orEmpty()
    return envValue.ifEmpty { null }
}

fun Project.booleanPropertyOrEnv(propertyName: String, envName: String): Boolean? =
    when (stringPropertyOrEnv(propertyName, envName)?.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        null -> null
        else -> null
    }

fun String.toBuildConfigStringLiteral(): String = buildString {
    append('"')
    this@toBuildConfigStringLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

fun Project.commandOutput(vararg args: String): Provider<String> =
    providers.exec {
        commandLine(*args)
    }.standardOutput.asText.map { it.trim() }

fun Project.resolvedNdkIdentity(workspaceRootDir: File): Provider<String> =
    providers.provider {
        fun envPath(name: String): File? = System.getenv(name)?.takeIf(String::isNotBlank)?.let(::File)

        val envNdk = listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME")
            .asSequence()
            .mapNotNull(::envPath)
            .firstOrNull(File::isDirectory)
        if (envNdk != null) {
            return@provider "env:${envNdk.name}"
        }

        val envSdk = listOf("ANDROID_SDK_ROOT", "ANDROID_HOME")
            .asSequence()
            .mapNotNull(::envPath)
            .firstOrNull(File::isDirectory)
        if (envSdk != null) {
            val sdkNdk = envSdk.resolve("ndk")
            val latest = sdkNdk.listFiles()
                ?.filter(File::isDirectory)
                ?.maxByOrNull(File::getName)
            if (latest != null) {
                return@provider "sdk:${latest.name}"
            }
        }

        val localPropertiesCandidates = listOf(
            project.rootProject.file("local.properties"),
            workspaceRootDir.resolve("local.properties"),
        )
        localPropertiesCandidates.forEach { localPropertiesFile ->
            if (!localPropertiesFile.isFile) {
                return@forEach
            }
            val properties = Properties()
            localPropertiesFile.inputStream().use(properties::load)
            val sdkDir = properties.getProperty("sdk.dir")?.takeIf(String::isNotBlank) ?: return@forEach
            val ndkRoot = File(sdkDir).resolve("ndk")
            val latest = ndkRoot.listFiles()
                ?.filter(File::isDirectory)
                ?.maxByOrNull(File::getName)
            if (latest != null) {
                return@provider "local-properties:${latest.name}"
            }
        }

        "unknown"
    }

val workspaceRootDir = rootProject.projectDir.resolve("../..").canonicalFile
val solarlabGeneratedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs/solarlab_v2")
val solarlabVersionCode = project.stringPropertyOrEnv("solarlab.versionCode", "SOLARLAB_VERSION_CODE")
    ?.toIntOrNull()
    ?: 1
val solarlabVersionName = project.stringPropertyOrEnv("solarlab.versionName", "SOLARLAB_VERSION_NAME")
    ?: "0.1.0-alpha.1"
val solarlabDevTelemetryEndpoint = project.stringPropertyOrEnv(
    "solarlab.devTelemetryEndpoint",
    "SOLARLAB_DEV_TELEMETRY_ENDPOINT",
) ?: ""
val solarlabDevTelemetryToken = project.stringPropertyOrEnv(
    "solarlab.devTelemetryToken",
    "SOLARLAB_DEV_TELEMETRY_TOKEN",
) ?: ""
val solarlabPreferredGpuBackend = project.stringPropertyOrEnv(
    "solarlab.preferredGpuBackend",
    "SOLARLAB_PREFERRED_GPU_BACKEND",
) ?: "none"
val solarlabDebugStageFirstClient = project.booleanPropertyOrEnv(
    "solarlab.debugStageFirstClient",
    "SOLARLAB_STAGE_FIRST_CLIENT",
) ?: true
val solarlabStageFirstRuntimeMirror = project.booleanPropertyOrEnv(
    "solarlab.stageFirstRuntimeMirror",
    "SOLARLAB_STAGE_FIRST_RUNTIME_MIRROR",
) ?: true
val solarlabHostedDebugProfile = project.stringPropertyOrEnv(
    "solarlab.hostedDebugProfile",
    "SOLARLAB_HOSTED_DEBUG_PROFILE",
)?.takeIf(String::isNotBlank) ?: "full-fidelity"
val solarlabHostedDebugLiteMode = when (solarlabHostedDebugProfile) {
    "full-fidelity" -> false
    "hosted-debug-lite" -> true
    else -> throw GradleException(
        "Unsupported solarlab.hostedDebugProfile=$solarlabHostedDebugProfile. " +
            "Expected one of: full-fidelity, hosted-debug-lite."
    )
}

val buildSolarlabNative by tasks.registering(BuildSolarlabNativeTask::class) {
    group = "build"
    description = "Builds and stages libsolarlab_v2.so for Android ABIs under build/generated."
    workspaceRootPath.set(workspaceRootDir.absolutePath)
    ffiCrateRelativePath.set("engine/ffi")
    androidRootLocalPropertiesPath.set(rootProject.file("local.properties").absolutePath)
    generatedJniLibsDir.set(solarlabGeneratedJniLibsDir)
    rustWorkspaceInputs.from(
        fileTree(workspaceRootDir) {
            include("Cargo.toml")
            include("Cargo.lock")
            include("engine/**/Cargo.toml")
            include("engine/**/src/**/*.rs")
            include("engine/ffi/include/**/*.h")
        }
    )
    rustcVersion.set(project.commandOutput("rustc", "-V"))
    cargoNdkVersion.set(project.commandOutput("cargo", "ndk", "--version"))
    ndkIdentity.set(project.resolvedNdkIdentity(workspaceRootDir))
}

android {
    namespace = "com.sednalabs.solarlab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sednalabs.solarlab"
        minSdk = 31
        targetSdk = 36
        versionCode = solarlabVersionCode
        versionName = solarlabVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEV_TELEMETRY_ENDPOINT", solarlabDevTelemetryEndpoint.toBuildConfigStringLiteral())
        buildConfigField("String", "DEV_TELEMETRY_TOKEN", solarlabDevTelemetryToken.toBuildConfigStringLiteral())
        buildConfigField("String", "PREFERRED_GPU_BACKEND", solarlabPreferredGpuBackend.toBuildConfigStringLiteral())
        buildConfigField("boolean", "STAGE_FIRST_RUNTIME_MIRROR", solarlabStageFirstRuntimeMirror.toString())
        buildConfigField("String", "HOSTED_DEBUG_PROFILE", solarlabHostedDebugProfile.toBuildConfigStringLiteral())
        buildConfigField("boolean", "HOSTED_DEBUG_LITE_MODE", solarlabHostedDebugLiteMode.toString())

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "STAGE_FIRST_CLIENT", solarlabDebugStageFirstClient.toString())
        }

        create("prerelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".internal"
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Solar Gravity Lab Dev Preview")
            buildConfigField("boolean", "STAGE_FIRST_CLIENT", "true")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "STAGE_FIRST_CLIENT", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(solarlabGeneratedJniLibsDir)
            assets.srcDir(workspaceRootDir.resolve("app/src/main/assets"))
        }
    }
}

val debugVariantNeedsRustRuntime = !solarlabDebugStageFirstClient || solarlabStageFirstRuntimeMirror
val stageFirstReleaseVariantsNeedRustRuntime = solarlabStageFirstRuntimeMirror

if (debugVariantNeedsRustRuntime) {
    tasks.matching { task ->
        task.name == "preDebugBuild" ||
            task.name == "preDebugAndroidTestBuild"
    }.configureEach {
        dependsOn(buildSolarlabNative)
    }
}

if (stageFirstReleaseVariantsNeedRustRuntime) {
    tasks.matching { task ->
        task.name == "prePrereleaseBuild" ||
            task.name == "preReleaseBuild"
    }.configureEach {
        dependsOn(buildSolarlabNative)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")

    implementation(project(":core-math"))
    implementation(project(":core-model"))
    implementation(project(":core-simulation"))
    implementation(project(":render-core"))
    implementation(project(":feature-lab"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
