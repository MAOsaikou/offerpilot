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

    @Test void localServiceNeverCallsHttpIncludingDirectoryAndRefresh() {
        reset(live);
        var local = new JobService(new JobSource(live, snapshot, "LOCAL"), jdbc, json);
        assertThat(local.sites().path("sites")).isNotEmpty();
        var item = local.search("Java", "bytedance", "campus", "北京").path("items").get(0);
        assertThat(local.detail(item.path("id").asText(), true).path("source").asText()).isEqualTo("LOCAL_SNAPSHOT");
        verifyNoInteractions(live);
    }

    @Test void chatToolCarriesSnapshotMetadataWhenDirectoryIsOffline() {
        var card = chat.search("Java", "字节跳动", "campus", "北京");
        assertThat(card.path("items")).isNotEmpty();
        assertThat(card.at("/meta/source").asText()).isEqualTo("LOCAL_SNAPSHOT");
        assertThat(card.at("/meta/snapshotDate").asText()).isEqualTo("2026-10-10");
        assertThat(card.at("/meta/fallbackReason").asText()).isEqualTo("LIVE_UNAVAILABLE");
    }

    @Test void mcpJobsSearchUsesLocalModeAndPreservesSourceWhileLimitingResults() {
        reset(live);
        var local = new JobService(new JobSource(live, snapshot, "LOCAL"), jdbc, json);
        var tools = new OfferPilotMcpTools(mock(ResumeDiagnoser.class), mock(JobMatcher.class), local, mock(ModelGateway.class), json);
        var result = tools.searchJobs("Java", null, "all", null, 1);
        assertThat(result.path("items")).hasSize(1);
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.at("/meta/source").asText()).isEqualTo("LOCAL_SNAPSHOT");
        verifyNoInteractions(live);
    }
}
