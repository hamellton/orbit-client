plugins {
    `java-library`
    `java-test-fixtures`
}

val generator: Configuration by configurations.creating

dependencies {
    api(project(":net"))
    generator(project(":protocol-gen"))
    testFixturesApi(project(":net"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val protocolDir = rootProject.layout.projectDirectory.dir("reference/protocol")
val generatedDir = layout.buildDirectory.dir("generated/sources/protocol/java")
// earlier client builds' protocols: only the tests read recordings by them (docs/PLAN.md, protocol updates)
val olderDir = layout.buildDirectory.dir("generated/sources/protocol-old/java")

val generateProtocol by tasks.registering(JavaExec::class) {
    description = "Generates packet classes from reference/protocol/*.json"
    classpath = generator
    mainClass.set("orbit.protocolgen.GeneratorMain")
    inputs.dir(protocolDir)
    outputs.dir(generatedDir)
    outputs.dir(olderDir)
    args(protocolDir.asFile.absolutePath, generatedDir.get().asFile.absolutePath, olderDir.get().asFile.absolutePath)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

sourceSets.main {
    java.srcDir(files(generatedDir).builtBy(generateProtocol))
}

sourceSets.named("testFixtures") {
    java.srcDir(files(olderDir).builtBy(generateProtocol))
}

listOf("compileJava", "compileTestFixturesJava").forEach { name ->
    tasks.named<JavaCompile>(name) {
        // generated code is large and mechanical; keep lint noise out of the build log
        options.compilerArgs.removeAll { it.startsWith("-Xlint") }
        options.compilerArgs.add("-Xlint:none")
    }
}

tasks.named<Test>("test") {
    // -Porbit.recordings=<dir> runs the recording checks on another set of sessions
    systemProperty("orbit.recordings", providers.gradleProperty("orbit.recordings")
        .getOrElse(rootProject.layout.projectDirectory.dir("recordings").asFile.absolutePath))
    maxHeapSize = "1g"
}
