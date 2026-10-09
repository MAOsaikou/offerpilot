package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.knowledge.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.mcp.server.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class KnowledgeNotesIntegrationTest {
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }
    @Autowired GuestIdentityService identities;
    @Autowired KnowledgeNoteService notes;
    @Autowired KnowledgeNoteSearch search;
    @Autowired OccupationEmbeddings embeddings;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KnowledgeNoteService.Draft DRAFT = new KnowledgeNoteService.Draft(
            "缓存失效面试复盘", "我在图书借阅项目中用 Redis 缓存热门图书，通过并发测试验证缓存失效后的数据库负载。下次应说明方案限制。", "面试复盘");
    @org.junit.jupiter.api.BeforeEach void clearNotes() { jdbc.execute("TRUNCATE knowledge_note_imports,knowledge_notes CASCADE"); }
    private String owner() { return identities.require("Bearer " + identities.create().accessToken()); }
    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/knowledge/notes" + path))
                .header("Content-Type", "application/json");
        if (token != null) req.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }

    @Test void crudPersistsAcrossRequestsAndEveryEndpointRequiresIdentity() throws Exception {
        String token = identities.create().accessToken();
        for (String method : List.of("POST", "PUT")) {
            assertThat(request(method, method.equals("POST") ? "" : "/unknown", null, DRAFT).statusCode()).isEqualTo(401);
        }
        for (String path : List.of("", "/unknown", "/search?q=Redis"))
            assertThat(request("GET", path, null, null).statusCode()).isEqualTo(401);
        assertThat(request("DELETE", "/unknown", null, null).statusCode()).isEqualTo(401);
        var imports = Map.of("notes", List.of(Map.of("sourceId", "local-1", "note", DRAFT)));
        assertThat(request("POST", "/import", null, imports).statusCode()).isEqualTo(401);
        assertThat(data(request("GET", "", token, null)).path("total").asInt()).isZero();
        var created = data(request("POST", "", token, DRAFT));
        String id = created.path("id").asText();
        assertThat(data(request("GET", "/" + id, token, null))).isEqualTo(created);
        assertThat(created.path("createdAt").asText()).isNotBlank();
        var changed = new KnowledgeNoteService.Draft("新的标题", "新的项目经历", "项目经历");
        assertThat(data(request("PUT", "/" + id, token, changed)).path("content").asText()).isEqualTo(changed.content());
        assertThat(data(request("GET", "", token, null)).path("total").asInt()).isEqualTo(1);
        String other = identities.create().accessToken();
        for (String method : List.of("GET", "PUT", "DELETE"))
            assertThat(request(method, "/" + id, other, method.equals("PUT") ? DRAFT : null).statusCode()).isEqualTo(404);
        assertThat(data(request("GET", "/search?q=项目", other, null)).path("items")).isEmpty();
        data(request("DELETE", "/" + id, token, null));
        assertThat(request("GET", "/" + id, token, null).statusCode()).isEqualTo(404);
    }

    @Test void invalidSearchAndPaginationDoNotChangeSavedData() throws Exception {
        String token = identities.create().accessToken();
        data(request("POST", "", token, DRAFT));
        for (String path : List.of("?page=0", "?page=abc", "?size=51", "/search", "/search?q=%20",
                "/search?q=Redis&limit=0", "/search?q=Redis&limit=abc", "/search?q="+"x".repeat(2001))) {
            var response = request("GET", path, token, null);
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).path("code").asInt()).isEqualTo(10001);
        }
        assertThat(data(request("GET", "", token, null)).path("total").asInt()).isEqualTo(1);
    }

    @Test void paginationAndChineseFtsArePrivateAndWorkWithoutAnyEmbeddingKey() {
        String owner = owner(), other = owner();
        var first = notes.create(owner, DRAFT);
        notes.create(owner, new KnowledgeNoteService.Draft("Java 集合", "HashMap 扩容与负载因子", "技术积累"));
        notes.create(other, DRAFT);
        assertThat(notes.list(owner, 1, 1).items()).hasSize(1);
        assertThat(notes.list(owner, 2, 1).items()).hasSize(1).doesNotContainAnyElementsOf(notes.list(owner, 1, 1).items());
        assertThat(notes.list(owner, 3, 1).items()).isEmpty();
        assertThat(notes.list(owner, 1, 20).total()).isEqualTo(2);
        assertThat(embeddings.configured()).isFalse();
        var found = search.search(owner, "缓存失效", 5);
        assertThat(found.vectorAvailable()).isFalse();
        assertThat(found.items()).extracting(KnowledgeNoteSearch.Hit::id).containsExactly(first.id());
        assertThat(search.search(owner, "redis", 5).items()).extracting(KnowledgeNoteSearch.Hit::id).containsExactly(first.id());
        assertThat(search.search(owner, "'; DROP TABLE knowledge_notes; --", 5).items()).isEmpty();
        notes.update(owner, first.id(), new KnowledgeNoteService.Draft("修改", "完全不同的内容", "求职心得"));
        assertThat(search.search(owner, "Redis", 5).items()).isEmpty();
        assertThatThrownBy(() -> notes.list(owner, 0, 51)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
    }

    @Test void importsRetryAtomicallyNeverOverwriteOrResurrectAndStayOwnerScoped() {
        String owner = owner(), other = owner();
        var batch = List.of(new KnowledgeNoteService.ImportEntry("legacy-1", DRAFT));
        assertThat(notes.importNotes(owner, batch)).isEqualTo(new KnowledgeNoteService.ImportResult(1, 0));
        String id = notes.list(owner, 1, 20).items().get(0).id();
        notes.update(owner, id, new KnowledgeNoteService.Draft("修改", "服务端修改内容", "技术积累"));
        assertThat(notes.importNotes(owner, batch)).isEqualTo(new KnowledgeNoteService.ImportResult(0, 1));
        assertThat(notes.get(owner, id).title()).isEqualTo("修改");
        notes.delete(owner, id);
        assertThat(notes.importNotes(owner, batch).alreadyImported()).isEqualTo(1);
        assertThat(notes.list(owner, 1, 20).total()).isZero();
        assertThat(notes.importNotes(other, batch).imported()).isEqualTo(1);
        var conflicting = List.of(new KnowledgeNoteService.ImportEntry("new", DRAFT),
                new KnowledgeNoteService.ImportEntry("legacy-1", new KnowledgeNoteService.Draft("不同原件", "保留本地内容", "技术积累")));
        assertThatThrownBy(() -> notes.importNotes(owner, conflicting)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        assertThat(notes.list(owner, 1, 20).total()).isZero();
        jdbc.update("DELETE FROM users WHERE id=?", other);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_note_imports WHERE user_id=?", Integer.class, other)).isZero();
    }

    @Test void bestEffortIndexingRetriesAndDoesNotOverwriteChangedOrDeletedNotes() {
        String owner = owner();
        var provider = mock(OccupationEmbeddings.class);
        when(provider.configured()).thenReturn(true);
        var worker = new KnowledgeNoteEmbeddingJob(jdbc, provider, true);
        var note = notes.create(owner, DRAFT);
        verifyNoInteractions(provider); // save only wrote an FTS + pending record
        when(provider.embed(anyList())).thenReturn(List.of());
        assertThat(worker.indexPending()).isZero();
        assertThat(search.search(owner, "Redis", 5).items()).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT embedding_next_attempt_at>now() FROM knowledge_notes WHERE id=?", Boolean.class, note.id())).isTrue();
        float[] vector = new float[1024]; vector[0] = 1;
        jdbc.update("UPDATE knowledge_notes SET embedding_next_attempt_at=now() WHERE id=?", note.id());
        when(provider.embed(anyList())).thenAnswer(call -> {
            notes.update(owner, note.id(), new KnowledgeNoteService.Draft("已编辑", "新内容", "技术积累"));
            return List.of(vector);
        });
        assertThat(worker.indexPending()).isZero();
        assertThat(jdbc.queryForObject("SELECT embedding IS NULL FROM knowledge_notes WHERE id=?", Boolean.class, note.id())).isTrue();
        when(provider.embed(anyList())).thenReturn(List.of(vector));
        assertThat(worker.indexPending()).isEqualTo(1);
        assertThat(worker.indexPending()).isZero();
        notes.update(owner, note.id(), DRAFT);
        when(provider.embed(anyList())).thenAnswer(call -> { notes.delete(owner, note.id()); return List.of(vector); });
        assertThat(worker.indexPending()).isZero();
    }

    @Test void hybridRrfFiltersBothCandidateSetsAndDegradesOnSupplierFailure() {
        String owner = owner(), other = owner();
        var own = notes.create(owner, DRAFT);
        var semantic = notes.create(owner, new KnowledgeNoteService.Draft("工程实践", "过载时减少数据库查询", "项目经历"));
        var foreign = notes.create(other, DRAFT);
        float[] vector = new float[1024]; vector[0] = 1;
        for (var note : List.of(own, semantic, foreign))
            jdbc.update("UPDATE knowledge_notes SET embedding=CAST(? AS vector) WHERE id=?", OccupationEmbeddings.literal(vector), note.id());
        var provider = mock(OccupationEmbeddings.class);
        when(provider.configured()).thenReturn(true);
        when(provider.embed(anyList())).thenReturn(List.of(vector));
        var hybrid = new KnowledgeNoteSearch(jdbc, provider);
        var found = hybrid.search(owner, "Redis", 10);
        assertThat(found.vectorAvailable()).isTrue();
        assertThat(found.items()).extracting(KnowledgeNoteSearch.Hit::id).containsExactly(own.id(), semantic.id());
        assertThat(found.items().get(0).lexicalMatch()).isTrue();
        assertThat(found.items().get(0).vectorMatch()).isTrue();
        when(provider.embed(anyList())).thenReturn(List.of());
        assertThat(hybrid.search(owner, "Redis", 10).items()).extracting(KnowledgeNoteSearch.Hit::id).containsExactly(own.id());
        assertThat(hybrid.search(owner, "Redis", 10).vectorAvailable()).isFalse();
    }
}
