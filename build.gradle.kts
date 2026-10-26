import org.spongepowered.gradle.plugin.config.PluginLoaders
import org.spongepowered.plugin.metadata.model.PluginDependency

plugins {
    `java-library`
    id("org.spongepowered.gradle.plugin") version "2.5.1"
    // Классы Minecraft (Mojang-имена) для компиляции: пакеты отправляются напрямую.
    id("org.spongepowered.gradle.vanilla") version "0.2.2"
}

group = "dev.selviz"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.enginehub.org/repo/") {
        name = "enginehub"
    }
}

dependencies {
    // Только для компиляции: WorldEdit на сервере ставится отдельным плагином.
    // 7.3.6–7.3.8 — последние сборки WorldEdit под Sponge API 12 (Minecraft 1.21/1.21.1).
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.8")
}

sponge {
    apiVersion("12.0.0")
    minecraftVersion("1.21.1")
    license("MIT")
    loader {
        name(PluginLoaders.JAVA_PLAIN)
        version("1.0")
    }
    plugin("we-selection-display") {
        displayName("WE Selection Display")
        entrypoint("dev.selviz.SelectionDisplayPlugin")
        description("Показывает игроку его выделение WorldEdit клиентскими display-сущностями (block_display).")
        dependency("spongeapi") {
            loadOrder(PluginDependency.LoadOrder.AFTER)
            optional(false)
        }
        dependency("worldedit") {
            loadOrder(PluginDependency.LoadOrder.AFTER)
            optional(false)
        }
    }
}

minecraft {
    version("1.21.1")
}

val javaTarget = 21
java {
    sourceCompatibility = JavaVersion.toVersion(javaTarget)
    targetCompatibility = JavaVersion.toVersion(javaTarget)
    if (JavaVersion.current() < JavaVersion.toVersion(javaTarget)) {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaTarget))
    }
}

tasks.withType(JavaCompile::class).configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaTarget)
}

tasks.withType(AbstractArchiveTask::class).configureEach {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}
