plugins {
    idea
    // https://projects.neoforged.net/neoforged/ModDevGradle
    id("net.neoforged.moddev")
}

val modId = "bulkterminal"
val modVersion = "0.1.0-beta"

base.archivesName = modId
version = modVersion
group = "com.dloi"

// 依赖版本集中在此，方便一处升级（26.1.2 移植基线，与 MEGA 26.1 分支的编译基线对齐）
val ae2Version = "26.1.10-beta"
val guideMeVersion = "26.1.14-beta"
val mcVersion = "26.1.2"
val neoForgeVersion = "26.1.2.81"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

// MDG 会自行注入 NeoForge / Minecraft 仓库；这里额外补 Modrinth Maven，用于拉取 ae2 与 guideme
// MEGA Cells 的 26.1 移植（4.12.0）尚未发布到 Modrinth，使用从其 26.1 分支本地构建的 jar
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

    // AE2 硬性依赖 guideme；编译期也要（覆写 getHelpTopic 引用 PageAnchor）
    implementation("maven.modrinth:guideme:$guideMeVersion")

    // MEGA Cells 4.12.0（MC 26.1.2 移植）——他人项目产物，不入本仓库，需自行构建：
    //   git clone https://github.com/62832/MEGACells && cd MEGACells && git checkout 26.1
    //   GITHUB_REF_TYPE=tag GITHUB_REF_NAME=4.12.0 ./gradlew jar
    //   mkdir -p libs && cp build/libs/megacells-4.12.0.jar libs/
    // （版本号必须经 GITHUB_REF_* 环境变量给出，否则 MEGA 构建脚本会落到 0.0.0，
    //  不满足本模组 mods.toml 的 [4.12.0,) 依赖范围）
    // 注：dev 运行时装载 MEGA 请把 jar 放入 run/client/mods
    compileOnly(files("libs/megacells-4.12.0.jar"))
}

neoForge {
    version = neoForgeVersion

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
