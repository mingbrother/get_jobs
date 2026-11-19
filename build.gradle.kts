import java.time.LocalDate
import java.io.File
import org.springframework.boot.gradle.tasks.run.BootRun
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    java
    id("org.springframework.boot") version "3.5.7"
}

group = "com.getjobs"
version = "2.0.1"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.7"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.apache.httpcomponents.client5:httpclient5-fluent")
    implementation("com.microsoft.playwright:playwright:1.51.0")
    implementation("com.baomidou:mybatis-plus-spring-boot3-starter:3.5.9")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("com.baomidou:mybatis-plus-generator:3.5.9")
    implementation("org.freemarker:freemarker:2.3.32")
    implementation("org.json:json:20231013")
    implementation("io.github.cdimascio:dotenv-java:2.2.0")
    implementation("jakarta.annotation:jakarta.annotation-api:2.1.1")

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")
    testCompileOnly("org.projectlombok:lombok:1.18.42")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.42")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation"))
}

springBoot {
    buildInfo()
}

// 前端构建配置
val frontendDir = layout.projectDirectory.dir("front").asFile
val frontendDistDir = layout.projectDirectory.dir("src/main/resources/dist").asFile

val buildFrontend = tasks.register("buildFrontend") {
    group = "frontend"
    description = "Build Next.js frontend and copy to src/main/resources/dist"

    onlyIf {
        frontendDir.isDirectory
    }

    doLast {
        val node = findOnPath("node")
        if (node == null) {
            logger.warn("Node not found in PATH, skipping frontend build")
            return@doLast
        }

        // Install dependencies if needed
        if (!File(frontendDir, "node_modules/next").isDirectory) {
            val corepack = findOnPath("corepack")
            if (corepack == null) {
                logger.warn("Frontend dependencies not installed and corepack not found, skipping")
                return@doLast
            }
            logger.lifecycle("Installing frontend dependencies...")
            providers.exec {
                workingDir = frontendDir
                commandLine(corepack.absolutePath, "pnpm", "install")
                isIgnoreExitValue = true
            }.result.get()
        }

        // Build frontend
        logger.lifecycle("Building frontend (next build)...")
        providers.exec {
            workingDir = frontendDir
            commandLine(node.absolutePath, "node_modules/next/dist/bin/next", "build")
        }.result.get().assertNormalExitValue()

        // Copy dist to resources
        logger.lifecycle("Copying build output to src/main/resources/dist...")
        providers.exec {
            workingDir = frontendDir
            commandLine(node.absolutePath, "scripts/copy-dist.mjs")
        }.result.get().assertNormalExitValue()

        logger.lifecycle("Frontend built: $frontendDistDir")
    }
}

// 确保 processResources 依赖前端构建
tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
    exclude("resume", "resume.*")
}

// 查找可执行文件
fun findOnPath(name: String): File? {
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val candidates = if (isWindows) listOf("$name.cmd", "$name.exe", "$name.bat", name) else listOf(name)
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

tasks.named<BootRun>("bootRun") {
    systemProperty("file.encoding", "UTF-8")
    systemProperty("sun.stdout.encoding", "UTF-8")
    systemProperty("sun.stderr.encoding", "UTF-8")
    systemProperty("LOG_DATE", LocalDate.now().toString())
}
