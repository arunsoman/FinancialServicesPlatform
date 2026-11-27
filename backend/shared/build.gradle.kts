dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // Stage 1 rails: circuit breaker + bulkhead for connector admission (plan §8.2).
    // Core 2.4.x = plain Java, no Spring wiring, safe inside the "plain library" contract.
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")
    implementation("io.github.resilience4j:resilience4j-bulkhead:2.4.0")
}

// shared is a plain library (JwtService, Es256, ActorAssertion, PkiKeyResolver) consumed by
// the runnable modules — no Spring application class, so no bootJar. The root build applies
// the Boot plugin to every source project; here we keep the normal thin jar instead.
// Without this, ./gradlew build fails on ':shared:bootJar' ("mainClass could not be resolved").
tasks {
    bootJar { enabled = false }
    jar { enabled = true }
}
