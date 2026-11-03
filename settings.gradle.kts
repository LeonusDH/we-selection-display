pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.spongepowered.org/repository/maven-public/") {
            name = "sponge"
        }
    }
}

plugins {
    // Автоматически скачивает JDK для toolchain, если подходящего нет на машине.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "we-selection-display"
