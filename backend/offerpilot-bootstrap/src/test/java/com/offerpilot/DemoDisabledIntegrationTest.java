package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DemoDisabledIntegrationTest extends SharedHttpIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test void disabledPublicLoginReturns403WithoutCreatingDemoData() throws Exception {
        int resumesBefore = jdbc.queryForObject("SELECT count(*) FROM resumes", Integer.class);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/auth/demo-login"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(20007);
        assertThat(json.readTree(response.body()).path("message").asText()).isEqualTo("体验账号未启用");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE is_demo=true", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resumes", Integer.class)).isEqualTo(resumesBefore);
    }
}
