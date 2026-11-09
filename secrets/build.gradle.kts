plugins {
    `java-library`
    application
}

dependencies {
    api(libs.jackson.databind)
    compileOnly(libs.logback.classic)
    runtimeOnly(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("orbit.secrets.VaultTool")
    applicationName = "vault"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
