plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-math"))
    implementation(project(":core-model"))
    implementation(project(":core-simulation"))

    testImplementation(libs.junit)
}
