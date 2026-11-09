plugins { `java-library` }

dependencies {
    api(project(":protocol"))
    api(project(":auth"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback.classic)
}

val gameCheck by tasks.registering(JavaExec::class) {
    description = "Live game connection check: --args='--creds creds.txt --seconds 60'"
    classpath = sourceSets.main.get().runtimeClasspath + configurations.testRuntimeClasspath.get()
    mainClass.set("orbit.game.GameCheck")
    workingDir = rootProject.projectDir
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
