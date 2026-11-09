plugins {
    `java-library`
    application
}

dependencies {
    api(project(":world"))
    api(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback.classic)
}

application {
    mainClass.set("orbit.config.ConfigTool")
    applicationName = "configs"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
