plugins { `java-library` }

dependencies {
    api(project(":protocol"))
    api(project(":auth"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback.classic)
}
