package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.job.application.*;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.mcp.JobsMcpClient;
import com.offerpilot.mcp.OfferPilotMcpTools;
import com.offerpilot.resume.application.ResumeDiagnoser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "offerpilot.jobs.source-mode=AUTO", "spring.ai.model.embedding=none", "spring.ai.mcp.server.enabled=false",
        "offerpilot.resume.worker-enabled=false"})
@ActiveProfiles("test") @Testcontainers
class JobsSnapshotHttpIntegrationTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", db::getJdbcUrl);
        p.add("spring.datasource.username", db::getUsername);
        p.add("spring.datasource.password", db::getPassword);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired GuestIdentityService identities;
    @Autowired JobService jobs;
    @Autowired JobSearchForChat chat;
    @Autowired JobSnapshot snapshot;
    @Autowired JdbcTemplate jdbc;
    @MockBean JobsMcpClient live;
    @MockBean JobMatcher matcher;
    @Autowired JobMatchService matches;
    @Autowired JobMatchForChat chatMatches;

    @BeforeEach void unavailable() {
        when(live.call(anyString(), anyMap())).thenThrow(new BusinessException(ErrorCode.TOOL_FAILED));
    }

    @Test void restAutoFailureReturns200AndPersistsSnapshotForDetailAndMatch() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/jobs?query=Java&site=bytedance&location=" + java.net.URLEncoder.encode("上海", java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + identities.create().accessToken()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var result = json.readTree(response.body()).path("data");
        assertThat(result.at("/meta/source").asText()).isEqualTo("LOCAL_SNAPSHOT");
        assertThat(result.at("/meta/fallbackReason").asText()).isEqualTo("LIVE_UNAVAILABLE");
        JsonNode item = result.path("items").get(0);
        assertThat(item.path("externalId").asText()).startsWith("snapshot-");
        assertThat(item.path("source").asText()).isEqualTo("LOCAL_SNAPSHOT");
        assertThat(item.path("snapshotDate").asText()).isEqualTo("2026-10-10");
        assertThat(JobService.evidenceText(item)).contains("Java", "Spring Boot", "MySQL");
        clearInvocations(live);
        assertThat(jobs.detail(item.path("id").asText(), true)).isEqualTo(item);
        verifyNoInteractions(live);
    }

    @Test void chatToolCarriesSnapshotMetadataWhenDirectoryIsOffline() {
        var card = chat.search("Java", "字节跳动", "campus", "北京");
        assertThat(card.path("items")).isNotEmpty();
        assertThat(card.at("/meta/source").asText()).isEqualTo("LOCAL_SNAPSHOT");
        assertThat(card.at("/meta/snapshotDate").asText()).isEqualTo("2026-10-10");
        assertThat(card.at("/meta/fallbackReason").asText()).isEqualTo("LIVE_UNAVAILABLE");
    }

    @Test void expiredDemoAndAutoStoredCopiesWorkForRestDetailsAndBothMatchEntries() throws Exception {
        var guest = identities.create();
        String token = guest.accessToken();
        String owner = identities.require("Bearer " + token);
        String resumeId = "resume-fallback-" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?, ?, 'RESUME', 'test', ?, 'resume.txt', 'text/plain', 10, ?)", resumeId, owner, resumeId, "0".repeat(64));
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,'虚构简历',?,'READY')", resumeId, owner, resumeId);
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES (?,?,?,1,'UPLOAD','{\"summary\":\"Java项目\"}'::jsonb,'Java项目')", resumeId, resumeId, owner);
        jdbc.update("UPDATE resumes SET current_version_id=? WHERE id=?", resumeId, resumeId);
        when(matcher.match(any(), any())).thenAnswer(invocation -> json.readTree("""
            {"summary":"核实过的Java依据","score":100,"requirements":[{"requirement":"Java","status":"MET","jdQuote":"Java","resumePath":"/summary","resumeQuote":"Java项目","reason":"项目证据","suggestion":"核对细节"}]}
            """));
        for (String source : new String[]{"demo", "company-career-site", "LOCAL_SNAPSHOT"}) {
            String id = "job_" + java.util.UUID.randomUUID();
            var payload = json.createObjectNode().put("site", source.equals("demo") ? "demo" : "tencent")
                .put("externalId", id).put("nature", "intern").put("title", "Java实习生").put("source", source).put("requirement", "Java");
            jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload,detail_fetched_at) VALUES (?,?,?,'intern',?::jsonb,now()-interval '1 hour')", id, payload.path("site").asText(), id, payload.toString());
            clearInvocations(live);
            var get = http("GET", "/jobs/" + id, token, null);
            assertThat(get.statusCode()).isEqualTo(200);
            assertThat(json.readTree(get.body()).at("/data/source").asText()).isEqualTo(source);
            verifyNoInteractions(live); // GET remains a stored read in every mode.
            var rest = http("POST", "/jobs/" + id + "/matches", token, java.util.Map.of("resumeId", resumeId,
                "resumeVersionId", resumeId, "requestId", java.util.UUID.randomUUID().toString()));
            assertThat(rest.statusCode()).isEqualTo(200);
            var report = json.readTree(rest.body()).path("data");
            String expected = source.equals("company-career-site") ? "STORED_SNAPSHOT" : source;
            assertThat(report.at("/jobSnapshot/source").asText()).isEqualTo(expected);
            var card = chatMatches.match(owner, id, resumeId, resumeId, java.util.UUID.randomUUID().toString());
            assertThat(card.cardType()).isEqualTo("job_match");
            if (!source.equals("company-career-site")) verifyNoInteractions(live);
            else verify(live, times(2)).call(eq("get_job_detail"), anyMap());
        }
    }
    private HttpResponse<String> http(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
