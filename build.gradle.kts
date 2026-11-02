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
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    // Cascade, the UI library the config screen is built with (https://github.com/skies-starred/cascade)
    maven("https://maven.starred.foo/releases")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    implementation("net.fabricmc:fabric-language-kotlin:${prop("deps.fabric_kotlin")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")

    runtimeOnly("me.djtheredstoner:DevAuth-fabric:${prop("deps.devauth")}")

    // Bundled so it needn't be installed separately; published per Minecraft version as <version>+<minecraft>
    val cascade = "foo.starred:cascade:${prop("deps.cascade")}+$minecraftVersion"
    implementation(cascade)
    include(cascade)
    // SVG rasterizer for CascadeSVG, which Cascade doesn't bundle
    implementation("com.github.weisj:jsvg:${prop("deps.jsvg")}")
    include("com.github.weisj:jsvg:${prop("deps.jsvg")}")
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
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") {
            expand(props)
        }
    }

    compileKotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
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
