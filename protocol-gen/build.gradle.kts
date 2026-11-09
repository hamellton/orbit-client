plugins { application }

dependencies {
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("orbit.protocolgen.GeneratorMain")
}
