package com.offerpilot;

import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.auth.application.JwtTokenService;
import com.offerpilot.common.document.DocumentTextReader;
import com.offerpilot.knowledge.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import(KnowledgeNotesIntegrationTest.Storage.class)
class StaffKnowledgeIntegrationTest extends SharedHttpIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired StaffKnowledgeDocuments documents;
    @Autowired JwtTokenService tokens;
    @Autowired GuestIdentityService guests;
    @Autowired com.offerpilot.resume.application.ResumeObjectStore storage;
    @Autowired com.offerpilot.privacy.AccountDataCleanup cleanup;
    @LocalServerPort int port;

    private String staff() {
        String id="staff-kb-"+java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname,roles) VALUES (?,?, 'fixture','Staff','{STAFF}')",id,id+"@example.test");
        return id;
    }
    private HttpResponse<String> request(String path,String token,String method) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/staff/knowledge"+path));
        if(token!=null) req.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(req.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void uploadedStaffDocumentIndexesSeparatelyAndReindexDeletionCleanOriginals() throws Exception {
        String owner=staff(), token=tokens.issue(owner,List.of("STAFF"),"Staff");
        var doc=documents.upload(owner,"ops.md","text/markdown","Redis 缓存故障处理手册".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var provider=mock(OccupationEmbeddings.class);
        when(provider.fingerprint()).thenReturn("none@disabled");
        var worker=new StaffKnowledgeIndexJob(jdbc,tx,provider);
        var debug=new RetrievalDebug(jdbc,provider);
        assertThat(doc.indexStatus()).isEqualTo("QUEUED");
        assertThat(debug.search("Redis",10,"staff_docs").items()).isEmpty();
        worker.indexPending();
        assertThat(documents.get(doc.id()).indexStatus()).isEqualTo("READY");
        assertThat(documents.get(doc.id()).indexError()).contains("仅全文检索");
        assertThat(documents.get(doc.id()).chunkCount()).isEqualTo(1);
        assertThat(debug.search("Redis",10,"staff_docs").items()).extracting(RetrievalDebug.Hit::id).contains(doc.id()+"_0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_notes WHERE user_id=?",Integer.class,owner)).isZero();
        assertThat(request("/documents/"+doc.id(),token,"GET").statusCode()).isEqualTo(200);
        assertThat(request("/documents/"+doc.id()+"/reindex",token,"POST").statusCode()).isEqualTo(200);
        assertThat(documents.get(doc.id()).indexStatus()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_knowledge_chunks WHERE document_id=?",Integer.class,doc.id())).isZero();
        var file=jdbc.queryForMap("SELECT f.id,f.bucket,f.object_key FROM files f JOIN staff_knowledge_documents d ON d.file_id=f.id WHERE d.id=?",doc.id());
        assertThat(request("/documents/"+doc.id(),token,"DELETE").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE id=?",Integer.class,file.get("id"))).isZero();
        jdbc.queryForList("SELECT id FROM account_data_cleanup WHERE object_key=?",String.class,file.get("object_key")).forEach(cleanup::clean);
        assertThatThrownBy(()->storage.get((String)file.get("bucket"),(String)file.get("object_key"))).isInstanceOf(RuntimeException.class);
        for(String credential:new String[]{null,guests.create().accessToken(),tokens.issue(guests.require("Bearer "+guests.create().accessToken()),List.of("USER"),"User")}) {
            assertThat(request("/documents",credential,"GET").statusCode()).isIn(401,403);
        }
        jdbc.update("UPDATE users SET roles='{USER}' WHERE id=?",owner);
        assertThat(request("/documents",token,"GET").statusCode()).isEqualTo(403);
        assertThat(request("/retrieval-debug?query=Redis&collection=staff_docs",token,"GET").statusCode()).isEqualTo(403);
    }
    @Test void retriesAbandonedLeasesAndRejectsStaleResultsAfterReindexOrDelete() {
        String owner=staff();
        var doc=documents.upload(owner,"retry.txt","text/plain","Redis runbook".getBytes());
        var provider=mock(OccupationEmbeddings.class);
        when(provider.fingerprint()).thenReturn("bge-m3@local"); when(provider.configured()).thenReturn(true);
        var worker=new StaffKnowledgeIndexJob(jdbc,tx,provider);
        when(provider.embed(anyList())).thenReturn(List.of());
        for(int attempt=0;attempt<3;attempt++) {
            jdbc.update("UPDATE staff_knowledge_documents SET next_attempt_at=now() WHERE id=?",doc.id()); worker.indexPending();
        }
        assertThat(documents.get(doc.id()).indexStatus()).isEqualTo("FAILED");
        assertThat(documents.get(doc.id()).indexError()).doesNotContain("Exception","http");
        documents.reindex(doc.id());
        float[] vector=new float[1024];vector[0]=1;
        when(provider.embed(anyList())).thenAnswer(call->{ documents.reindex(doc.id()); return List.of(vector); });
        worker.indexPending();
        assertThat(documents.get(doc.id()).indexStatus()).isEqualTo("QUEUED");
        when(provider.embed(anyList())).thenReturn(List.of(vector));worker.indexPending();
        assertThat(documents.get(doc.id()).indexStatus()).isEqualTo("READY");
        assertThat(new RetrievalDebug(jdbc,provider).search("Redis",5,"staff_docs").vectorAvailable()).isTrue();
        documents.reindex(doc.id());
        when(provider.embed(anyList())).thenAnswer(call->{ documents.delete(doc.id());return List.of(vector); });
        worker.indexPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_knowledge_chunks WHERE document_id=?",Integer.class,doc.id())).isZero();
        var abandoned=documents.upload(owner,"abandoned.txt","text/plain","Lease fixture".getBytes());
        jdbc.update("UPDATE staff_knowledge_documents SET index_status='INDEXING',index_attempts=3,next_attempt_at=now() WHERE id=?",abandoned.id());
        worker.indexPending();assertThat(documents.get(abandoned.id()).indexStatus()).isEqualTo("FAILED");
        documents.delete(abandoned.id());
    }
    @Test void failedStorageWriteLeavesNoRowsAndDurableCleanupReceipt() {
        String owner=staff();
        var broken=mock(com.offerpilot.resume.application.ResumeObjectStore.class);
        when(broken.bucket()).thenReturn("failed-fixture");
        doThrow(new IllegalStateException("fixture outage")).when(broken).put(anyString(),any(),anyString());
        doThrow(new IllegalStateException("fixture cleanup outage")).when(broken).remove(anyString(),anyString());
        var service=new StaffKnowledgeDocuments(jdbc,tx,broken,new DocumentTextReader());
        assertThatThrownBy(()->service.upload(owner,"rollback.txt","text/plain","Rollback".getBytes())).hasMessageContaining("存储暂不可用");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_knowledge_documents WHERE created_by=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE user_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_data_cleanup WHERE bucket='failed-fixture'",Integer.class)).isPositive();
    }
}
