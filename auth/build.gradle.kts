plugins { `java-library` }

dependencies {
    api(libs.slf4j.api)
    api(project(":secrets"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback.classic)
}

val authCheck by tasks.registering(JavaExec::class) {
    description = "Live login check (--args='--account main', ORBIT_DOSID env or --args='--creds creds.txt')"
    classpath = sourceSets.main.get().runtimeClasspath + configurations.testRuntimeClasspath.get()
    mainClass.set("orbit.auth.AuthCheck")
    systemProperty("logback.configurationFile", "orbit/secrets/logback-check.xml")
    standardInput = System.`in`
    workingDir = rootProject.projectDir
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
