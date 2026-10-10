package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationTaskHttpIntegrationTest extends SharedHttpIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(String token, String owner, String suffix) {}

    private Fixture fixture() {
        var session = guests.create();
        String token = session.accessToken();
        String owner = guests.require("Bearer " + token);
        String suffix = owner.replaceAll("[^a-zA-Z0-9]", "");
        suffix = suffix.substring(0, Math.min(12, suffix.length()));
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,?,?,?,?,?,?,?)",
                "file_gen_" + suffix, owner, "RESUME", "bucket", "key-" + suffix, "f.txt", "text/plain", 1, "sha");
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,?,?,?)",
                "res_gen_" + suffix, owner, "fixture", "file_gen_" + suffix, "READY");
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES (?,?,?,1,'UPLOAD','{}','')",
                "ver_gen_" + suffix, "res_gen_" + suffix, owner);
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,?,?,?,?::jsonb)",
                "job_gen_" + suffix, "site", "ext-" + suffix, "campus", "{}");
        return new Fixture(token, owner, suffix);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }


    @Test
    void cancelsRunningMatchOptimizeAndDiagnosisForOwnerOnly() throws Exception {
        var fx = fixture();
        String matchId = "match_cancel_" + fx.suffix();
        String optId = "opt_cancel_" + fx.suffix();
        String diagId = "diag_cancel_" + fx.suffix();
        jdbc.update("INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status) VALUES (?,?,?,?,?,'RUNNING')",
                matchId, fx.owner(), "job_gen_" + fx.suffix(), "ver_gen_" + fx.suffix(), "req_cancel_m_" + fx.suffix());
        jdbc.update("""
                INSERT INTO resume_optimizations(id,user_id,resume_id,version_id,request_id,status,job_description,model_tier)
                VALUES (?,?,?,?,?,'RUNNING','fixture jd','FLASH')
                """, optId, fx.owner(), "res_gen_" + fx.suffix(), "ver_gen_" + fx.suffix(), "req_cancel_o_" + fx.suffix());
        jdbc.update("""
                INSERT INTO resume_diagnoses(id,user_id,resume_id,version_id,request_id,status,model_tier)
                VALUES (?,?,?,?,?,'RUNNING','FLASH')
                """, diagId, fx.owner(), "res_gen_" + fx.suffix(), "ver_gen_" + fx.suffix(), "req_cancel_d_" + fx.suffix());

        assertThat(get("/generation-tasks", fx.token()).body()).contains(matchId, optId, diagId);
        assertThat(get("/generation-tasks", null).statusCode()).isEqualTo(401);

        var stranger = guests.create();
        assertThat(post("/generation-tasks/JOB_MATCH/" + matchId + "/cancel", stranger.accessToken()).statusCode())
                .isEqualTo(404);
        assertThat(post("/generation-tasks/JOB_MATCH/" + matchId + "/cancel", null).statusCode()).isEqualTo(401);
        assertThat(post("/generation-tasks/UNKNOWN/" + matchId + "/cancel", fx.token()).statusCode()).isEqualTo(400);

        assertThat(post("/generation-tasks/JOB_MATCH/" + matchId + "/cancel", fx.token()).statusCode()).isEqualTo(200);
        assertThat(post("/generation-tasks/RESUME_OPTIMIZE/" + optId + "/cancel", fx.token()).statusCode()).isEqualTo(200);
        assertThat(post("/generation-tasks/RESUME_DIAGNOSIS/" + diagId + "/cancel", fx.token()).statusCode()).isEqualTo(200);

        // idempotent second cancel
        assertThat(post("/generation-tasks/JOB_MATCH/" + matchId + "/cancel", fx.token()).statusCode()).isEqualTo(200);

        JsonNode remaining = JSON.readTree(get("/generation-tasks", fx.token()).body()).path("data");
        assertThat(remaining.toString()).doesNotContain(matchId, optId, diagId);
        assertThat(jdbc.queryForObject("SELECT status FROM job_matches WHERE id=?", String.class, matchId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM resume_optimizations WHERE id=?", String.class, optId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM resume_diagnoses WHERE id=?", String.class, diagId)).isEqualTo("CANCELLED");
    }
}
