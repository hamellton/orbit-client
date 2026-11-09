plugins { `java-library` }

dependencies {
    api(project(":protocol"))
    implementation(libs.slf4j.api)
    implementation(libs.jackson.databind)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback.classic)
}

tasks.named<Test>("test") {
    systemProperty("orbit.recordings", rootProject.layout.projectDirectory.dir("recordings").asFile.absolutePath)
}

// map facts (reference/maps/*.json) ship inside the jar
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("reference/maps")) {
        include("*.json")
        into("orbit/world/maps")
    }
}
