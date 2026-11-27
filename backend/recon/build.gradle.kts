dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // Read-only cross-book checks (plan §2): no own DB, no writes — only probes + paging.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web") // RestClient only; server disabled (web-application-type: none)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
