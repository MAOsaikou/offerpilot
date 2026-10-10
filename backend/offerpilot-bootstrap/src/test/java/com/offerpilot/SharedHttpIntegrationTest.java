package com.offerpilot;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One offline context for HTTP suites with identical infrastructure. Each test owns its fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.mcp.server.enabled=false",
        "offerpilot.resume.worker-enabled=false", "offerpilot.staff-bootstrap.email=", "offerpilot.staff-bootstrap.password="})
@ActiveProfiles("test")
abstract class SharedHttpIntegrationTest {
    // JVM-scoped container: JUnit must not stop it while another suite reuses the cached context.
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    static { DATABASE.start(); }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }
}
