package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP/security/forwarded-header chain + disposable database; test profile has no Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.mcp.server.enabled=false",
        "offerpilot.rate-limit.auth-guest-per-minute=3", "offerpilot.rate-limit.auth-login-per-minute=3",
        "offerpilot.rate-limit.auth-register-per-minute=3", "offerpilot.rate-limit.auth-staff-login-per-minute=3",
        "offerpilot.rate-limit.auth-demo-login-per-minute=3", "offerpilot.demo.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class AuthRateLimitHttpIntegrationTest {
    @Container
    static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Test
    void sameIpGuestCreationStopsBeforeWritingMoreUsersAndSessions() throws Exception {
        int usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        int sessionsBefore = jdbc.queryForObject("SELECT count(*) FROM guest_sessions", Integer.class);
        for (int i = 0; i < 3; i++) {
            var response = post("guest", "192.0.2.1", "{}");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-RateLimit-Remaining")).contains(Integer.toString(2 - i));
        }
        assertLimited(post("guest", "192.0.2.1", "{}"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(usersBefore + 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_sessions", Integer.class)).isEqualTo(sessionsBefore + 3);
        assertThat(post("guest", "192.0.2.2", "{}").statusCode()).isEqualTo(200);
        // A depleted guest bucket does not consume the login bucket.
        assertThat(post("login", "192.0.2.1", credentials("missing@example.com", "wrong-password")).statusCode()).isEqualTo(401);
    }

    @Test
    void failedPasswordLoginsAreCountedAndEventuallyLimited() throws Exception {
        String email = "rate-login-" + UUID.randomUUID() + "@example.com";
        assertThat(post("register", "192.0.2.3", credentials(email, "correct-password")).statusCode()).isEqualTo(200);
        for (int i = 0; i < 3; i++) {
            var response = post("login", "192.0.2.3", credentials(email, "wrong-password"));
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(ErrorCode.BAD_CREDENTIALS.code());
        }
        // Even correct credentials cannot bypass an exhausted IP bucket.
        assertLimited(post("login", "192.0.2.3", credentials(email, "correct-password")));
    }

    @Test
    void staffLoginHasItsOwnBruteForceBucket() throws Exception {
        String body = credentials("missing-staff@example.com", "wrong-password");
        for (int i = 0; i < 3; i++) assertThat(post("staff/login", "192.0.2.4", body).statusCode()).isEqualTo(401);
        assertLimited(post("staff/login", "192.0.2.4", body));
        assertThat(post("login", "192.0.2.4", body).statusCode()).isEqualTo(401);
    }

    @Test
    void demoLoginHasItsOwnBucketEvenWhenDisabled() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(post("demo-login", "192.0.2.7", "{}").statusCode()).isEqualTo(403);
        assertLimited(post("demo-login", "192.0.2.7", "{}"));
        assertThat(post("login", "192.0.2.7", credentials("missing@example.com", "wrong-password")).statusCode()).isEqualTo(401);
    }

    @Test
    void registerCountsMalformedBodiesAndStopsBeforeCreatingAccount() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(post("register", "192.0.2.5", "{").statusCode()).isEqualTo(400);
        String email = "rate-register-" + UUID.randomUUID() + "@example.com";
        assertLimited(post("register", "192.0.2.5", credentials(email, "correct-password")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?", Integer.class, email)).isZero();
    }

    @Test
    void normalDirectRegisterLoginAndGuestSucceedAndPreflightIsNotCounted() throws Exception {
        String body = credentials("normal-" + UUID.randomUUID() + "@example.com", "correct-password");
        for (String path : new String[]{"register", "login", "guest"}) {
            var response = post(path, null, path.equals("guest") ? "{}" : body);
            assertThat(response.statusCode()).isEqualTo(200);
            var envelope = json.readTree(response.body());
            assertThat(envelope.path("code").asInt()).isZero();
            assertThat(envelope.path("data").path("accessToken").asText()).isNotBlank();
            assertThat(response.headers().firstValue("X-RateLimit-Remaining")).contains("2");
        }
        var preflight = client.send(HttpRequest.newBuilder(uri("guest"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("X-RateLimit-Limit")).isEmpty();
        var verify = client.send(HttpRequest.newBuilder(uri("verify")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(verify.statusCode()).isEqualTo(401);
        assertThat(verify.headers().firstValue("X-RateLimit-Limit")).isEmpty();
    }

    @Test
    void frameworkResolvesTrustedProxyIpBeforeLimitingAndCorsSurvives429() throws Exception {
        for (int i = 0; i < 4; i++) {
            // This harness simulates a trusted proxy: Forwarded is authoritative even when
            // X-Forwarded-For differs. The filter itself never reads either raw header.
            var response = client.send(HttpRequest.newBuilder(uri("guest"))
                    .header("Content-Type", "application/json")
                    .header("Origin", "http://localhost:5173")
                    .header("Forwarded", "for=192.0.2.6;proto=http")
                    .header("X-Forwarded-For", "192.0.2." + (100 + i))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            if (i < 3) assertThat(response.statusCode()).isEqualTo(200);
            else {
                assertLimited(response);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
                assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow()).contains("Retry-After");
            }
        }
    }

    private HttpResponse<String> post(String path, String ip, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        // Unique test IPs isolate scenarios; these headers simulate sanitized trusted-proxy input.
        if (ip != null) builder.header("X-Forwarded-For", ip);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/v1/auth/" + path); }
    private String credentials(String email, String password) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }

    private void assertLimited(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
        assertThat(response.headers().firstValue("X-RateLimit-Limit")).contains("3");
        assertThat(response.headers().firstValue("X-RateLimit-Remaining")).contains("0");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        var body = json.readTree(response.body());
        assertThat(body.path("code").asInt()).isEqualTo(ErrorCode.RATE_LIMITED.code());
        assertThat(body.path("message").asText()).isEqualTo(ErrorCode.RATE_LIMITED.message());
        assertThat(body.path("traceId").asText()).isNotBlank();
        assertThat(response.headers().firstValue("X-Trace-Id")).contains(body.path("traceId").asText());
    }
}
