package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.knowledge.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.context.annotation.Import(KnowledgeNotesIntegrationTest.Storage.class)
class KnowledgeNotesIntegrationTest extends SharedHttpIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class Storage {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.offerpilot.resume.application.ResumeObjectStore objects() {
            var objects = new java.util.concurrent.ConcurrentHashMap<String,byte[]>();
            return new com.offerpilot.resume.application.ResumeObjectStore() {
                public String bucket() { return "kb-fixture"; }
                public void put(String key, byte[] bytes, String mime) { objects.put(key, bytes.clone()); }
                public byte[] get(String bucket, String key) {
                    byte[] bytes = objects.get(key);
                    if (bytes == null) throw new IllegalStateException("missing fixture object");
                    return bytes.clone();
                }
                public void remove(String bucket, String key) { objects.remove(key); }
            };
        }
    }
    @Autowired GuestIdentityService identities;
    @Autowired KnowledgeNoteService notes;
    @Autowired KnowledgeDocuments documents;
    @Autowired com.offerpilot.resume.application.ResumeObjectStore storage;
    @Autowired com.offerpilot.privacy.AccountDataCleanup cleanup;
    @Autowired KnowledgeNoteSearch search;
    @Autowired OccupationEmbeddings embeddings;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
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

    private HttpResponse<String> upload(String token, String name, String mime, byte[] bytes) throws Exception {
        String boundary = "kb-" + UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--" + boundary + "--\r\n").getBytes());
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/knowledge/notes/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(req.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void uploadedDocumentsArePrivateSearchableAndOriginalsAreCleanedOnDeletion() throws Exception {
        String token = identities.create().accessToken(), owner = identities.require("Bearer " + token);
        String other = identities.create().accessToken();
        byte[] bytes = "Redis 缓存失效的项目经验😀".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(upload(null, "project.md", "text/markdown", bytes).statusCode()).isEqualTo(401);
        var created = data(upload(token, "project.md", "text/markdown", bytes));
        String id = created.path("id").asText(), file = created.path("sourceFileId").asText();
        assertThat(created.path("indexStatus").asText()).isEqualTo("READY");
        assertThat(created.path("sourceFilename").asText()).isEqualTo("project.md");
        assertThat(created.path("title").asText()).isEqualTo("project");
        assertThat(created.path("category").asText()).isEqualTo("技术积累");
        assertThat(data(request("GET", "", token, null)).path("items").get(0)).isEqualTo(created);
        assertThat(search.search(owner, "缓存失效", 5).items()).extracting(KnowledgeNoteSearch.Hit::id).contains(id);
        assertThat(new KnowledgeNotesForChat(search).retrieve(owner, "Redis")).extracting(item -> item.noteId()).contains(id);
        assertThat(request("GET", "/"+id+"/file", other, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/"+id+"/file", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/"+id+"/file", token, null).body()).isEqualTo(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(documents.read(owner, id).bytes()).isEqualTo(bytes);
        var object = jdbc.queryForMap("SELECT bucket,object_key FROM files WHERE id=?", file);
        data(request("DELETE", "/"+id, token, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE id=?", Integer.class, file)).isZero();
        jdbc.queryForList("SELECT id FROM account_data_cleanup WHERE object_key=?", String.class, object.get("object_key")).forEach(cleanup::clean);
        assertThatThrownBy(() -> storage.get((String)object.get("bucket"), (String)object.get("object_key"))).isInstanceOf(RuntimeException.class);
        for (String name : List.of("wrong.pdf", "wrong.docx")) assertThat(upload(token,name,"application/octet-stream",bytes).statusCode()).isEqualTo(400);
        assertThat(upload(token,"bad.txt","text/plain",new byte[]{(byte)0xff}).statusCode()).isEqualTo(400);
        assertThat(notes.list(owner,1,20).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE user_id=?",Integer.class,owner)).isZero();
        var longNote = documents.upload(owner, "😀".repeat(125)+".TXT", "text/plain", "😀".repeat(30001).getBytes(java.nio.charset.StandardCharsets.UTF_8), "项目经历");
        assertThat(longNote.title().codePointCount(0,longNote.title().length())).isEqualTo(120);
        assertThat(longNote.content().codePointCount(0,longNote.content().length())).isEqualTo(30000);
        assertThat(longNote.sourceTruncated()).isTrue();
        notes.delete(owner,longNote.id());
        cleanup.retryPending();
    }

    @Test void databaseFailureRollsBackRowsAndRetainsFailedObjectCleanupForRetry() {
        String owner = owner();
        var rejectingNotes = new KnowledgeNoteService(jdbc, embeddings, false) {
            @Override public Note create(String user, Draft draft) {
                super.create(user, draft);
                throw new IllegalStateException("fixture database-stage failure");
            }
        };
        var retainedKey = new java.util.concurrent.atomic.AtomicReference<String>();
        var failingCleanup = new com.offerpilot.resume.application.ResumeObjectStore() {
            public String bucket() { return storage.bucket(); }
            public void put(String key, byte[] bytes, String mime) { retainedKey.set(key); storage.put(key,bytes,mime); }
            public byte[] get(String bucket, String key) { return storage.get(bucket,key); }
            public void remove(String bucket, String key) { throw new IllegalStateException("fixture cleanup outage"); }
        };
        var upload = new KnowledgeDocuments(jdbc, tx, failingCleanup, new com.offerpilot.common.document.DocumentTextReader(), rejectingNotes);
        assertThatThrownBy(() -> upload.upload(owner,"rollback.txt","text/plain","private fixture".getBytes(),null)).hasMessageContaining("fixture database-stage failure");
        assertThat(notes.list(owner,1,20).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE user_id=?",Integer.class,owner)).isZero();
        assertThat(storage.get(storage.bucket(),retainedKey.get())).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_data_cleanup WHERE object_key=?",Integer.class,retainedKey.get())).isEqualTo(1);
        cleanup.retryPending();
        assertThatThrownBy(() -> storage.get(storage.bucket(),retainedKey.get())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_data_cleanup WHERE object_key=?",Integer.class,retainedKey.get())).isZero();
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
        String body = "😀背景描述。".repeat(300) + "\n我采用 Redis 缓存失效策略，避免雪崩。\n" + "后续说明。".repeat(300);
        var longNote = notes.create(owner, new KnowledgeNoteService.Draft("长篇项目复盘", body, "项目经历"));
        var hit = search.search(owner, "Redis 缓存失效", 5).items().get(0);
        assertThat(hit.id()).isEqualTo(longNote.id());
        assertThat(hit.excerpt()).contains("Redis 缓存失效策略");
        assertThat(body).contains(hit.excerpt());
        assertThat(hit.excerpt().codePointCount(0, hit.excerpt().length())).isEqualTo(1000);
        assertThat(new com.offerpilot.knowledge.application.KnowledgeNotesForChat(search).retrieve(owner, "Redis 缓存失效").get(0).excerpt())
                .contains("Redis 缓存失效策略");
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
        var configuredNotes = new KnowledgeNoteService(jdbc, provider, true);
        var note = configuredNotes.create(owner, DRAFT);
        assertThat(note.indexStatus()).isEqualTo("QUEUED");
        verify(provider, never()).embed(anyList()); // save only wrote an FTS + pending record
        when(provider.embed(anyList())).thenReturn(List.of());
        assertThat(worker.indexPending()).isZero();
        assertThat(jdbc.queryForObject("SELECT embedding_attempts FROM knowledge_notes WHERE id=?", Integer.class, note.id())).isEqualTo(1);
        assertThat(notes.get(owner, note.id()).indexStatus()).isEqualTo("QUEUED");
        for (int i = 0; i < 2; i++) {
            jdbc.update("UPDATE knowledge_notes SET embedding_next_attempt_at=now() WHERE id=?", note.id());
            assertThat(worker.indexPending()).isZero();
        }
        assertThat(notes.get(owner, note.id()).indexStatus()).isEqualTo("FAILED");
        assertThat(notes.get(owner, note.id()).indexError()).contains("全文检索仍可用");
        notes.update(owner, note.id(), DRAFT);
        assertThat(search.search(owner, "Redis", 5).items()).isNotEmpty();

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
        assertThat(notes.get(owner, note.id()).indexStatus()).isEqualTo("READY");
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
