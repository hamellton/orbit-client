plugins { `java-library` }

dependencies {
    api(project(":config"))
    implementation(libs.slf4j.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.logback.classic)
}
