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

    @Test
    void listsRunningMatchForOwner() throws Exception {
        var session = guests.create();
        String token = session.accessToken();
        String owner = guests.require("Bearer " + token);
        String suffix = owner.replaceAll("[^a-zA-Z0-9]", "").substring(0, Math.min(12, owner.replaceAll("[^a-zA-Z0-9]", "").length()));
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,?,?,?,?,?,?,?)",
                "file_gen_" + suffix, owner, "RESUME", "bucket", "key-" + suffix, "f.txt", "text/plain", 1, "sha");
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,?,?,?)",
                "res_gen_" + suffix, owner, "fixture", "file_gen_" + suffix, "READY");
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES (?,?,?,1,'UPLOAD','{}','')",
                "ver_gen_" + suffix, "res_gen_" + suffix, owner);
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,?,?,?,?::jsonb)",
                "job_gen_" + suffix, "site", "ext-" + suffix, "campus", "{}");
        jdbc.update("INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status) VALUES (?,?,?,?,?,'RUNNING')",
                "match_gen_" + suffix, owner, "job_gen_" + suffix, "ver_gen_" + suffix, "req_gen_" + suffix);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/generation-tasks"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        JsonNode data = JSON.readTree(response.body()).path("data");
        assertThat(data.toString()).contains("match_gen_" + suffix);
        assertThat(data.toString()).contains("JOB_MATCH");
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/generation-tasks"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
}
