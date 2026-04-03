plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-math"))
    implementation(project(":core-model"))

    testImplementation(libs.junit)
}

tasks.register<JavaExec>("generatePhysicsAccuracyTelemetryReport") {
    group = "verification"
    description = "Generate deterministic physics-accuracy telemetry artifacts (JSON + Markdown)."

    val reportDir = layout.buildDirectory.dir("reports/physics-accuracy")
    val jsonOutput = reportDir.map { it.file("physics-accuracy-report.json") }
    val markdownOutput = reportDir.map { it.file("physics-accuracy-report.md") }

    mainClass.set("com.graciousgazelles.solarlab.core.simulation.PhysicsAccuracyTelemetryCli")
    classpath = sourceSets["main"].runtimeClasspath
    args(
        "--json-output",
        jsonOutput.get().asFile.absolutePath,
        "--markdown-output",
        markdownOutput.get().asFile.absolutePath,
    )
}
