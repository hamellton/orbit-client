plugins {
    `java-library`
    application
}

// installDist: game/build/install/game/bin/game-check (a real console for the master password prompt)
application {
    mainClass.set("orbit.game.GameCheck")
    applicationName = "game-check"
    applicationDefaultJvmArgs = listOf("-Dlogback.configurationFile=orbit/secrets/logback-check.xml")
}

dependencies {
    api(project(":protocol"))
    api(project(":auth"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback.classic)
    runtimeOnly(libs.logback.classic)
}

val gameCheck by tasks.registering(JavaExec::class) {
    description = "Live game connection check: --args='--account main --seconds 60' (or --creds, ORBIT_DOSID env)"
    classpath = sourceSets.main.get().runtimeClasspath + configurations.testRuntimeClasspath.get()
    mainClass.set("orbit.game.GameCheck")
    systemProperty("logback.configurationFile", "orbit/secrets/logback-check.xml")
    standardInput = System.`in`
    workingDir = rootProject.projectDir
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

