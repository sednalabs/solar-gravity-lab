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

    mainClass.set("com.graciousgazelles.solarlab.core.simulation.PhysicsAccuracyTelemetryCli")
    classpath = sourceSets["main"].runtimeClasspath
}
