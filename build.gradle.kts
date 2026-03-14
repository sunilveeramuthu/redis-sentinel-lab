subprojects {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        "implementation"(libs.findLibrary("slf4j-api").get())
        "runtimeOnly"(libs.findLibrary("logback-classic").get())
    }
}
