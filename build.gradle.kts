plugins {
    idea
    // https://projects.neoforged.net/neoforged/ModDevGradle
    id("net.neoforged.moddev")
}

val modId = "bulkterminal"
val modVersion = "0.1.1-beta"

base.archivesName = modId
version = modVersion
group = "com.dloi"

// 依赖版本集中在此，方便一处升级
val ae2Version = "19.2.18"
val megaVersion = "4.11.0"
val guideMeVersion = "21.1.19"
val mcVersion = "1.21.1"
val neoForgeVersion = "21.1.248"
val parchmentVersion = "2024.11.17"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

// MDG 会自行注入 NeoForge / Minecraft 仓库；这里额外补 Modrinth Maven，用于拉取 ae2 与 megacells
repositories {
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth Maven"
        content {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    // 运行时由玩家单独安装，这里只进编译 / 开发期 classpath，不打包进本模组 jar
    implementation("maven.modrinth:ae2:$ae2Version")
    implementation("maven.modrinth:mega:$megaVersion")

    // AE2 19.2.15 硬性依赖 guideme >= 21.1.1；编译期也要（覆写 getHelpTopic 引用 PageAnchor）
    implementation("maven.modrinth:guideme:$guideMeVersion")
}

neoForge {
    version = neoForgeVersion

    parchment {
        minecraftVersion = mcVersion
        mappingsVersion = parchmentVersion
    }

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        create("client") {
            client()
            gameDirectory = file("run/client")
        }

        create("server") {
            server()
            gameDirectory = file("run/server")
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf("version" to modVersion)
        inputs.properties(props)

        filesMatching("META-INF/neoforge.mods.toml") {
            expand(props)
        }
    }

    jar {
        manifest {
            attributes(
                "Specification-Title" to "Bulk Terminal",
                "Specification-Version" to modVersion,
                "Implementation-Title" to modId,
                "Implementation-Version" to modVersion
            )
        }
    }
}
