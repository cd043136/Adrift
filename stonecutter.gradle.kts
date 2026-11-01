plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom") apply false
    kotlin("jvm") apply false
}

stonecutter active "26.3"

tasks.register("buildAll") {
    group = "build"
    description = "Builds the mod jar for every Minecraft version"
    dependsOn(stonecutter.tasks.named("build"))
}
