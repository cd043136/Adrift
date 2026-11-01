import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
}

fun prop(key: String): String = sc.properties[key]

val minecraftVersion = sc.current.version
version = prop("mod.version")

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    maven("https://api.modrinth.com/maven")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    implementation("net.fabricmc:fabric-language-kotlin:${prop("deps.fabric_kotlin")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")

    runtimeOnly("me.djtheredstoner:DevAuth-fabric:${prop("deps.devauth")}")
    implementation("maven.modrinth:odin:${prop("deps.odin")}")

    implementation("com.github.stivais:Commodore:${prop("deps.commodore")}")

    prop("deps.lwjgl").let { lwjglVersion ->
        implementation("org.lwjgl:lwjgl-nanovg:$lwjglVersion")

        listOf("windows", "linux", "macos", "macos-arm64").forEach { os ->
            implementation("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-$os")
        }
    }
}

loom {
    runConfigs.named("client") {
        generateRunConfig = true
        // Shared between versions so DevAuth/options don't need setting up per version
        runDir = "../../run"
        jvmArguments.addAll(
            "-Dmixin.debug.export=true",
            "-Ddevauth.enabled=true",
            "-Ddevauth.account=main",
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-XX:+AllowEnhancedClassRedefinition"
        )
        jvmArguments.add(provider {
            "-javaagent:${configurations.compileClasspath.get().find { it.name.contains("sponge-mixin") }}"
        })
    }

    runConfigs.named("server") {
        generateRunConfig = false
    }
}

tasks {
    processResources {
        val props = mapOf(
            "version" to project.version.toString(),
            "mod_id" to prop("mod.id"),
            "mod_name" to prop("mod.name"),
            "mod_description" to prop("mod.description"),
            "minecraft_version_range" to prop("mod.mc_compat"),
            "loader_version" to prop("deps.fabric_loader"),
            "fabric_api_version" to prop("deps.fabric_api"),
            "fabric_kotlin_version" to prop("deps.fabric_kotlin"),
            "odin_version" to prop("deps.odin"),
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") {
            expand(props)
        }
    }

    compileKotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
            freeCompilerArgs.add("-Xlambdas=class") //Commodore
        }
    }

    compileJava {
        sourceCompatibility = "25"
        targetCompatibility = "25"
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    // Collects every version's jar into the root build/libs
    register<Copy>("collectJar") {
        group = "build"
        from(jar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs"))
    }

    build {
        finalizedBy("collectJar")
    }
}

base {
    archivesName.set(prop("mod.id"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("${project.version}-$minecraftVersion")
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}
