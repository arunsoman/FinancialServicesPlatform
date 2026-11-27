dependencies {
    implementation(project(":shared"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.postgresql:postgresql")

    // TigerBeetle client (must match server 0.17.9)
    implementation("com.tigerbeetle:tigerbeetle-java:0.17.9")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
