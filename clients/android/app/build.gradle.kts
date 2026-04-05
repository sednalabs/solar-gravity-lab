import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import javax.inject.Inject

abstract class BuildSolarlabNativeTask : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    @get:Input
    abstract val workspaceRootPath: Property<String>

    @get:Input
    abstract val ffiCrateRelativePath: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustWorkspaceInputs: ConfigurableFileCollection

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
        project.delete(outputDir)
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

        val sdkRoot = listOf("ANDROID_SDK_ROOT", "ANDROID_HOME")
            .mapNotNull { System.getenv(it) }
            .map(::File)
            .firstOrNull(File::isDirectory)
            ?: return null

        val ndkRoot = sdkRoot.resolve("ndk")
        if (!ndkRoot.isDirectory) {
            return null
        }

        return ndkRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.maxWithOrNull(::compareNdkDirectories)
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

val workspaceRootDir = rootProject.projectDir.resolve("../..").canonicalFile
val solarlabGeneratedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs/solarlab_v2")

val buildSolarlabNative by tasks.registering(BuildSolarlabNativeTask::class) {
    group = "build"
    description = "Builds and stages libsolarlab_v2.so for Android ABIs under build/generated."
    workspaceRootPath.set(workspaceRootDir.absolutePath)
    ffiCrateRelativePath.set("engine/ffi")
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
    outputs.upToDateWhen { false }
}

android {
    namespace = "com.sednalabs.solarlab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sednalabs.solarlab"
        minSdk = 31
        targetSdk = 35
        versionCode = 11
        versionName = "0.1.0-alpha.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        create("prerelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".internal"
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Solar Gravity Lab Dev Preview")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(solarlabGeneratedJniLibsDir)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildSolarlabNative)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    androidTestImplementation(composeBom)
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
