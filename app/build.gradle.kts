plugins { application }

dependencies {
    implementation(project(":game"))
    implementation(project(":sniffer"))
    implementation(libs.flatlaf)
    implementation(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("orbit.app.OrbitMain")
    applicationName = "orbit"
    // the sniffer jar has its own logback.xml; the app's configuration (with secret masking) wins
    applicationDefaultJvmArgs = listOf("-Dlogback.configurationFile=orbit/app/logback-app.xml")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

tasks.named<Test>("test") {
    // -Porbit.recordings=<dir> runs the recording checks on another set of sessions
    systemProperty("orbit.recordings", providers.gradleProperty("orbit.recordings")
        .getOrElse(rootProject.layout.projectDirectory.dir("recordings").asFile.absolutePath))
}
