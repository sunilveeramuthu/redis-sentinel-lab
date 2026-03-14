plugins {
    application
}

application {
    mainClass.set("com.redislab.isolated.IsolatedStorageExperiment")
}

dependencies {
    implementation(project(":experiment-core"))
}
