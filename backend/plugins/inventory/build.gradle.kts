plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

group = "com.fsp.plugin"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Boot 4 modularization: Flyway auto-configuration lives here (flyway-core alone never migrates)
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<JavaCompile> {
    options.release = 25
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("-proc:full")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
