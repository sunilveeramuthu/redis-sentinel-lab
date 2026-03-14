plugins {
    application
}

application {
    mainClass.set("com.redislab.shared.SharedStorageExperiment")
}

dependencies {
    implementation(project(":experiment-core"))
}
