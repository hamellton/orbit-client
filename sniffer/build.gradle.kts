plugins { application }

dependencies {
    implementation(project(":net"))
    implementation(libs.jackson.databind)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("orbit.sniffer.SnifferMain")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
