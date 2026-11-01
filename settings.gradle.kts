pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://jitpack.io")
    }

    val loom_version = providers.gradleProperty("loom_version").get()
    val kotlin_version = providers.gradleProperty("kotlin_version").get()

    plugins {
        id("net.fabricmc.fabric-loom") version loom_version
        kotlin("jvm") version kotlin_version
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

stonecutter {
    create(rootProject) {
        versions("26.1", "26.2", "26.3")
        vcsVersion = "26.3"
    }
}

rootProject.name = "Adrift"
