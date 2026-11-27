dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(project(":shared"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.session:spring-session-core")
    // Sessions live in Postgres, not in the JVM: a deploy / BFF restart no longer signs everyone out
    // (staging 2026-11-25: every deploy → proxy_no_session → back to the IdP). Boot 4 picks the
    // store from the classpath; `spring.session.store-type: jdbc` alone did nothing.
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")

    // Lombok: @Slf4j only (AGENTS.md §2b/§2c) — BOM-managed version
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.wiremock:wiremock-standalone:3.9.1")
}
