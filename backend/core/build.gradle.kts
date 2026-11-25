dependencies {
    implementation(project(":shared"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // F-12 email channel (V29) — JavaMailSender/SMTP transport, the one accepted notify dependency
    implementation("org.springframework.boot:spring-boot-starter-mail")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Boot 4 modularization: the Flyway AUTO-CONFIGURATION lives in this jar (raw flyway-core doesn't trigger it)
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.postgresql:postgresql")

    // TigerBeetle client (must match server 0.17.9)
    implementation("com.tigerbeetle:tigerbeetle-java:0.17.9")

    // Cryptography for audit hashes / tokens
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")

    // Lombok: @Slf4j only (AGENTS.md §2b/§2c) — BOM-managed version
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // spring-boot-starter-test is inherited from the root build (JUnit 5, Mockito, AssertJ)
}
