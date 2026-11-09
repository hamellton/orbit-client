plugins { `java-library` }

dependencies {
    api(project(":protocol"))
    implementation(libs.slf4j.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback.classic)
}

tasks.named<Test>("test") {
    systemProperty("orbit.recordings", rootProject.layout.projectDirectory.dir("recordings").asFile.absolutePath)
}
