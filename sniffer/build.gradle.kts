plugins { application }

dependencies {
    implementation(project(":protocol"))
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

val sessionReportScript by tasks.registering(CreateStartScripts::class) {
    mainClass.set("orbit.sniffer.SessionReport")
    applicationName = "session-report"
    outputDir = layout.buildDirectory.dir("session-report-scripts").get().asFile
    classpath = tasks.named<Jar>("jar").get().outputs.files + configurations.runtimeClasspath.get()
}

distributions.named("main") {
    contents { from(sessionReportScript) { into("bin") } }
}
