package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.StaffAccountBootstrap;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.config.OfferPilotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Real migrated PostgreSQL, runner and HTTP authentication; no external model calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=deepseek", "spring.ai.model.embedding=none",
        "spring.ai.deepseek.api-key=test-only-key", "spring.ai.mcp.server.enabled=false",
        "offerpilot.staff-bootstrap.email=", "offerpilot.staff-bootstrap.password="
})
@ActiveProfiles("test")
@Testcontainers
class StaffBootstrapIntegrationTest {
    @Container
    static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired OfferPilotProperties properties;
    @LocalServerPort int port;
    private static final String EMAIL = "bootstrap@example.com";
    private static final String PASSWORD = "isolated-bootstrap-password";

    @BeforeEach
    void freshUsers() {
        // Isolated container only: exercise the exact zero-STAFF condition independently of seed migrations.
        jdbc.execute("TRUNCATE TABLE users CASCADE");
    }

    private StaffAccountBootstrap bootstrap(String email, String password) {
        return new StaffAccountBootstrap(new OfferPilotProperties(null, null, null, null, null, null, null,
                new OfferPilotProperties.StaffBootstrap(email, password)), jdbc, passwords, transactions);
    }

    private void run(String email, String password) {
        bootstrap(email, password).run(new DefaultApplicationArguments());
    }

    private long staffCount() {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE 'STAFF' = ANY(roles)", Long.class);
    }

    @Test
    void createsTrimmedActiveStaffWithRegistrationHashAndCanLoginOverHttp() throws Exception {
        run("  " + EMAIL + "  ", PASSWORD);
        assertThat(staffCount()).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT id, nickname, status, password_hash FROM users WHERE email = ?", EMAIL);
        assertThat(row.get("id").toString()).startsWith("usr_");
        assertThat(row.get("nickname")).isEqualTo("bootstrap");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("password_hash").toString()).startsWith("$2a$12$");
        assertThat(passwords.matches(PASSWORD, row.get("password_hash").toString())).isTrue();
        var login = request("POST", "/auth/staff/login", null, Map.of("email", EMAIL, "password", PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(json.readTree(login.body()).path("data").path("user").path("roles").get(0).asText()).isEqualTo("STAFF");
    }

    @Test
    void staffDemotionImmediatelyRejectsExistingJwtOverHttp() throws Exception {
        run(EMAIL, PASSWORD);
        var login = request("POST", "/auth/staff/login", null, Map.of("email", EMAIL, "password", PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        String token = json.readTree(login.body()).path("data").path("accessToken").asText();
        assertThat(request("GET", "/staff/users", token, null).statusCode()).isEqualTo(200);

        assertThat(jdbc.update("UPDATE users SET roles = '{USER}' WHERE email = ?", EMAIL)).isEqualTo(1);
        var rejected = request("GET", "/staff/users", token, null);
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(json.readTree(rejected.body()).path("code").asInt()).isEqualTo(ErrorCode.FORBIDDEN.code());
        assertThat(json.readTree(rejected.body()).path("message").asText()).isEqualTo("需要客服账号权限");
    }

    @Test
    void repeatedRunsNeverChangePasswordOrAddStaff() {
        run(EMAIL, PASSWORD);
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, EMAIL);
        run(EMAIL, "different-password");
        run("another@example.com", "different-password");
        assertThat(staffCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, EMAIL)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'another@example.com'", Long.class)).isZero();
    }

    @Test
    void existingDisabledStaffAlsoPreventsCreation() {
        run("existing@example.com", PASSWORD);
        jdbc.update("UPDATE users SET status = 'DISABLED'");
        run(EMAIL, PASSWORD);
        assertThat(staffCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, EMAIL)).isZero();
    }

    @ParameterizedTest
    @CsvSource(value = {"'',''", "'  ','  '", "bootstrap@example.com,''", "'',isolated-password"})
    void blankOrPartialConfigurationSkipsWithoutFailure(String email, String password) {
        assertThat(properties.staffBootstrap().email()).isEmpty();
        assertThat(properties.staffBootstrap().password()).isEmpty();
        run(email, password);
        assertThat(staffCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"bad-email,isolated-password", "a@example.com,short"})
    void invalidCredentialsSkipWithoutFailure(String email, String password) {
        run(email, password);
        assertThat(staffCount()).isZero();
    }

    @Test
    void duplicateEmailNeverPromotesOrChangesUser() {
        String id = seed("existing", EMAIL, "2026-01-01T00:00:00Z");
        run(EMAIL.toUpperCase(), PASSWORD);
        assertThat(staffCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT array_to_string(roles, ',') FROM users WHERE id = ?", String.class, id)).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id)).isEqualTo("unusable-fixture");
    }

    @Test
    void concurrentRunnersCreateExactlyOneStaff() {
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> run(EMAIL, PASSWORD)),
                CompletableFuture.runAsync(() -> run("other@example.com", PASSWORD))).join();
        assertThat(staffCount()).isEqualTo(1);
    }

    @Test
    void listFiltersAuthoritativeGuestsAndReturnsStablePagesAndFilteredTotal() throws Exception {
        run(EMAIL, PASSWORD);
        var login = request("POST", "/auth/staff/login", null, Map.of("email", EMAIL, "password", PASSWORD));
        String token = json.readTree(login.body()).path("data").path("accessToken").asText();
        seed("usr_a", "a@example.com", "2026-01-01T00:00:00Z");
        seed("usr_b", "b@example.com", "2026-01-02T00:00:00Z");
        seed("usr_c", "c@example.com", "2026-01-03T00:00:00Z");
        seed("usr_d", "d@guest.invalid", "2026-01-03T00:00:00Z"); // email alone is not a guest marker
        String guest = seed("usr_guest", "guest@example.com", "2026-01-04T00:00:00Z");
        for (String hash : List.of("expired", "also-expired")) {
            jdbc.update("INSERT INTO guest_sessions(token_hash, user_id, expires_at) VALUES (?, ?, now() - interval '1 day')", hash, guest);
        }
        JsonNode first = page(token, "?page=1&size=2&hideGuests=true");
        assertThat(first.path("items").findValuesAsText("id")).containsExactly("usr_d", "usr_c");
        assertThat(first.path("total").asLong()).isEqualTo(4);
        assertThat(first.path("page").asInt()).isEqualTo(1);
        assertThat(first.path("size").asInt()).isEqualTo(2);
        JsonNode second = page(token, "?page=2&size=2&hideGuests=true");
        assertThat(second.path("items").findValuesAsText("id")).containsExactly("usr_b", "usr_a");
        assertThat(second.path("total").asLong()).isEqualTo(4);
        assertThat(page(token, "?page=3&size=2&hideGuests=true").path("items").size()).isZero();
        JsonNode defaults = page(token, "");
        assertThat(defaults.path("items").findValuesAsText("id")).containsExactly("usr_guest", "usr_d", "usr_c", "usr_b", "usr_a");
        assertThat(defaults.path("total").asLong()).isEqualTo(5);
        assertThat(defaults.path("page").asInt()).isEqualTo(1);
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        assertThat(page(token, "?size=1000").path("size").asInt()).isEqualTo(100);
        for (String query : List.of("?page=0", "?size=0", "?page=-1", "?size=-1")) {
            var invalid = request("GET", "/staff/users" + query, token, null);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(json.readTree(invalid.body()).path("code").asInt()).isEqualTo(10001);
        }
        assertThat(request("GET", "/staff/users", null, null).statusCode()).isEqualTo(401);
    }

    private String seed(String id, String email, String createdAt) {
        jdbc.update("INSERT INTO users(id, email, password_hash, nickname, created_at) VALUES (?, ?, 'unusable-fixture', ?, ?::timestamptz)",
                id, email, id, createdAt);
        return id;
    }

    private JsonNode page(String token, String query) throws Exception {
        var response = request("GET", "/staff/users" + query, token, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        return json.readTree(response.body()).path("data");
    }

    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
