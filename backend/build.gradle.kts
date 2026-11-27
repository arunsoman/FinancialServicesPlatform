plugins {
    java
    id("org.springframework.boot") version "4.0.9" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.fsp"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

subprojects {
    apply(plugin = "java")
    // Aggregator/container projects (e.g. ':plugins' — the parent of 'plugins:kyc',
    // implicitly registered by settings.gradle.kts) have no sources and must NOT get the
    // Spring Boot plugin: its bootJar task would fail with "mainClass could not be resolved",
    // breaking a plain ./gradlew build on the root project (seen 2026-11-19).
    val isSourceProject = projectDir.resolve("src/main/java").isDirectory
    if (isSourceProject) {
        apply(plugin = "org.springframework.boot")
        apply(plugin = "io.spring.dependency-management")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        implementation("org.slf4j:slf4j-api")
        implementation("org.projectlombok:lombok")
        annotationProcessor("org.projectlombok:lombok")
        testImplementation("org.springframework.boot:spring-boot-starter-test")
    }

    tasks.withType<JavaCompile> {
        options.release = 25
        // Spring MVC param-name discovery (path variables by name) requires -parameters in the bytecode:
        options.compilerArgs.add("-parameters")
        // JDK 23+ disables implicit annotation processing — Lombok (@Slf4j, AGENTS.md §2c) needs this:
        options.compilerArgs.add("-proc:full")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }}