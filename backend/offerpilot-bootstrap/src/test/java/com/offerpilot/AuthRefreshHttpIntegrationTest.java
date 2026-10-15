package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRefreshHttpIntegrationTest extends SharedHttpIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    HttpResponse<String> post(String path, String cookie, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth" + path)).header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", "op_refresh=" + cookie);
        return HttpClient.newHttpClient().send(b.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String cookie(HttpResponse<String> response) {
        String header = response.headers().allValues("Set-Cookie").stream().filter(h -> h.startsWith("op_refresh=")).findFirst().orElse("");
        return header.isEmpty() ? null : header.substring("op_refresh=".length(), header.indexOf(';'));
    }

    static String header(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(h -> h.startsWith("op_refresh=")).findFirst().orElse("");
    }

    JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }

    @Test void shortAccessTokenRefreshesThroughRotatingHttpOnlyCookie() throws Exception {
        var registered = post("/register", null, Map.of("email", UUID.randomUUID() + "@example.test", "password", "PrivateTest123!"));
        var login = data(registered);
        assertThat(login.path("expiresIn").asLong()).isEqualTo(15 * 60);
        assertThat(header(registered)).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=604800");
        String first = cookie(registered);
        assertThat(first).hasSizeGreaterThan(40);
        String userId = login.path("user").path("id").asText();
        // Only the hash is stored.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=? AND token_hash<>?", Integer.class, userId, first)).isEqualTo(1);

        var refreshed = post("/refresh", first, null);
        assertThat(data(refreshed).path("user").path("id").asText()).isEqualTo(userId);
        assertThat(data(refreshed).path("accessToken").asText()).isNotBlank();
        String second = cookie(refreshed);
        assertThat(second).isNotNull().isNotEqualTo(first);
        // Another tab racing with the just-rotated cookie still gets access, without a new cookie.
        var racing = post("/refresh", first, null);
        assertThat(data(racing).path("user").path("id").asText()).isEqualTo(userId);
        assertThat(cookie(racing)).isNull();

        // Reuse after the grace window means the cookie was copied: every session of the user ends.
        jdbc.update("UPDATE refresh_tokens SET revoked_at=now()-interval '1 minute' WHERE revoked_at IS NOT NULL AND user_id=?", userId);
        var stolen = post("/refresh", first, null);
        assertThat(stolen.statusCode()).isEqualTo(401);
        assertThat(header(stolen)).contains("Max-Age=0");
        assertThat(post("/refresh", second, null).statusCode()).isEqualTo(401);

        // Logging in again works; logout deletes that refresh token.
        var again = post("/login", null, Map.of("email", login.path("user").path("email").asText(), "password", "PrivateTest123!"));
        String third = cookie(again);
        assertThat(data(post("/refresh", third, null)).path("accessToken").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL", Integer.class, userId)).isEqualTo(1);
        var loggedOut = post("/logout", cookie(post("/login", null, Map.of("email", login.path("user").path("email").asText(), "password", "PrivateTest123!"))), null);
        assertThat(loggedOut.statusCode()).isEqualTo(200);
        assertThat(header(loggedOut)).contains("Max-Age=0");

        assertThat(post("/refresh", null, null).statusCode()).isEqualTo(401);
        assertThat(post("/refresh", "forged", null).statusCode()).isEqualTo(401);
        // A disabled account cannot refresh.
        var live = cookie(post("/login", null, Map.of("email", login.path("user").path("email").asText(), "password", "PrivateTest123!")));
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", userId);
        assertThat(post("/refresh", live, null).statusCode()).isEqualTo(401);
    }
}
