rootProject.name = "redis-sentinel-lab"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("experiment-core")
include("experiment-shared-storage")
include("experiment-isolated-storage")
