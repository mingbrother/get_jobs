// Application build, dependency management, and packaging.
import java.time.LocalDate
import org.gradle.language.jvm.tasks.ProcessResources

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.springframework.boot:spring-boot-gradle-plugin:3.2.0")
    }
}

plugins {
    java
}

apply(plugin = "org.springframework.boot")

group = "com.getjobs"
version = "2.0.1"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.0"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.apache.httpcomponents.client5:httpclient5-fluent")
    implementation("com.microsoft.playwright:playwright:1.62.0")
    implementation("com.baomidou:mybatis-plus-spring-boot3-starter:3.5.9")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("com.baomidou:mybatis-plus-generator:3.5.9")
    implementation("org.freemarker:freemarker:2.3.32")
    implementation("org.json:json:20231013")
    implementation("io.github.cdimascio:dotenv-java:2.2.0")
    implementation("jakarta.annotation:jakarta.annotation-api:2.1.1")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    val lombok = "org.projectlombok:lombok:1.18.42"
    compileOnly(lombok)
    annotationProcessor(lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation"))
}

tasks.named("bootJar") {
    dependsOn("installPatchrightDriver", "buildFrontend")
}

val patchrightVersion = "1.62.1"
val patchrightDriverDir = layout.buildDirectory.dir("patchright-driver")

val installPatchrightDriver = tasks.register("installPatchrightDriver") {
    group = "playwright"
    description = "Install patchright-core driver"

    val outDir = patchrightDriverDir
    outputs.dir(outDir)

    doLast {
        val driverDir = outDir.get().asFile
        val packageDir = File(driverDir, "package")
        val marker = File(driverDir, ".version")

        if (marker.isFile && marker.readText().trim() == patchrightVersion && File(packageDir, "cli.js").isFile) {
            logger.lifecycle("patchright-core $patchrightVersion ready: $packageDir")
            return@doLast
        }

        driverDir.deleteRecursively()
        driverDir.mkdirs()

        logger.lifecycle("Installing patchright-core@$patchrightVersion ...")
        val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
        providers.exec {
            commandLine(npm, "install", "patchright-core@$patchrightVersion",
                    "--prefix", driverDir.absolutePath, "--no-audit", "--no-fund", "--loglevel=error")
        }.result.get().assertNormalExitValue()

        val installed = File(driverDir, "node_modules/patchright-core")
        if (!File(installed, "cli.js").isFile) {
            throw GradleException("patchright-core cli.js not found: $installed")
        }
        copy {
            from(installed)
            into(packageDir)
        }
        marker.writeText(patchrightVersion)
        logger.lifecycle("patchright driver installed: $packageDir")
    }
}

val isWindowsOs = System.getProperty("os.name").lowercase().contains("windows")

fun findOnPath(name: String): File? {
    val candidates = if (isWindowsOs) listOf("$name.cmd", "$name.exe", "$name.bat", name) else listOf(name)
    val dirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
    for (dir in dirs) {
        if (dir.isBlank()) continue
        for (candidate in candidates) {
            val file = File(dir.trim(), candidate)
            if (file.isFile) return file
        }
    }
    return null
}

fun resolveNodePath(): String? = findOnPath("node")?.absolutePath

val frontendDir = layout.projectDirectory.dir("front").asFile
val frontendDistDir = layout.projectDirectory.dir("src/main/resources/dist").asFile

val buildFrontend = tasks.register("buildFrontend") {
    group = "frontend"
    description = "Build Next.js frontend"

    val forceRebuild = providers.gradleProperty("rebuildFrontend").isPresent

    onlyIf {
        when {
            !frontendDir.isDirectory -> {
                logger.lifecycle("No front directory, skipping frontend build")
                false
            }
            !forceRebuild && File(frontendDistDir, "index.html").isFile -> {
                logger.lifecycle("Frontend already built, skipping")
                false
            }
            else -> true
        }
    }

    doLast {
        val node = findOnPath("node")
        if (node == null) {
            logger.warn("Node not found in PATH, skipping frontend build")
            return@doLast
        }

        if (!File(frontendDir, "node_modules/next").isDirectory) {
            val corepack = findOnPath("corepack")
            if (corepack == null) {
                logger.warn("Frontend deps not installed, skipping")
                return@doLast
            }
            logger.lifecycle("Installing frontend deps...")
            providers.exec {
                workingDir = frontendDir
                commandLine(corepack.absolutePath, "pnpm", "install")
                isIgnoreExitValue = true
            }.result.get()
        }

        logger.lifecycle("Building frontend (next build)...")
        providers.exec {
            workingDir = frontendDir
            commandLine(node.absolutePath, "node_modules/next/dist/bin/next", "build")
        }.result.get().assertNormalExitValue()

        logger.lifecycle("Copying build output...")
        providers.exec {
            workingDir = frontendDir
            commandLine(node.absolutePath, "scripts/copy-dist.mjs")
        }.result.get().assertNormalExitValue()

        logger.lifecycle("Frontend built: $frontendDistDir")
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
    exclude("resume", "resume.*")
}
