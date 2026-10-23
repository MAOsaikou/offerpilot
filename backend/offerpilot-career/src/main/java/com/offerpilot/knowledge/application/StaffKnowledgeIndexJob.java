package com.offerpilot.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/** Durable leases and revision guards prevent stale workers from publishing after delete/reindex. */
@Component
public class StaffKnowledgeIndexJob {
    private static final Logger log = LoggerFactory.getLogger(StaffKnowledgeIndexJob.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OccupationEmbeddings embeddings;
    public StaffKnowledgeIndexJob(JdbcTemplate jdbc, TransactionTemplate tx, OccupationEmbeddings embeddings) {
        this.jdbc = jdbc; this.tx = tx; this.embeddings = embeddings;
    }
    private record Pending(String id, String title, String content, long revision, int attempt) {}
    @Scheduled(fixedDelayString = "${offerpilot.knowledge.staff-index.delay-ms:10000}", initialDelayString = "${offerpilot.knowledge.staff-index.delay-ms:10000}")
    public void tick() {
        try { indexPending(); }
        catch (RuntimeException ex) { log.warn("Staff indexing deferred ({})", ex.getClass().getSimpleName()); }
    }
    public int indexPending() {
        jdbc.update("""
                UPDATE staff_knowledge_documents SET index_status='FAILED',index_error='索引暂时失败，请点击重新索引重试',updated_at=now()
                WHERE index_status IN ('QUEUED','INDEXING') AND index_attempts>=3 AND next_attempt_at<=now()
                """);
        var pending = jdbc.query("""
                WITH pending AS (
                    SELECT id FROM staff_knowledge_documents WHERE index_status IN ('QUEUED','INDEXING')
                    AND next_attempt_at<=now() AND index_attempts<3 ORDER BY next_attempt_at,id LIMIT 2 FOR UPDATE SKIP LOCKED
                )
                UPDATE staff_knowledge_documents d SET index_status='INDEXING',index_error=NULL,
                    index_attempts=index_attempts+1,next_attempt_at=now()+interval '5 minutes',updated_at=now()
                FROM pending p WHERE d.id=p.id RETURNING d.id,d.title,d.content,d.revision,d.index_attempts
                """, (r,n) -> new Pending(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getInt(5)));
        int indexed = 0;
        for (var p : pending) {
            try {
                var chunks = chunks(p.content());
                String fingerprint = embeddings.fingerprint();
                boolean vector = BuiltinKnowledgeIndex.local(fingerprint) && embeddings.configured();
                var vectors = vector ? embeddings.embed(chunks.stream().map(c -> p.title()+"\n"+c).toList()) : List.<float[]>of();
                if (vector && (vectors.size() != chunks.size() || !fingerprint.equals(embeddings.fingerprint())))
                    throw new IllegalStateException("Embedding unavailable");
                // Validate before transaction; provider calls and chunking never hold row locks.
                var literals = vectors.stream().map(OccupationEmbeddings::literal).toList();
                Boolean saved = tx.execute(status -> {
                    var live = jdbc.queryForList("""
                            SELECT id FROM staff_knowledge_documents WHERE id=? AND revision=? AND index_attempts=?
                            AND index_status='INDEXING' FOR UPDATE
                            """, String.class, p.id(), p.revision(), p.attempt());
                    if (live.isEmpty()) return false;
                    jdbc.update("DELETE FROM staff_knowledge_chunks WHERE document_id=?", p.id());
                    for (int i=0;i<chunks.size();i++) jdbc.update("""
                            INSERT INTO staff_knowledge_chunks(id,document_id,ordinal,content,tsv,embedding,embedding_fingerprint)
                            VALUES (?,?,?,?,to_tsvector('simple',?),?::vector,?)
                            """, p.id()+"_"+i,p.id(),i,chunks.get(i),OccupationDocument.fulltext(p.title()+"\n"+chunks.get(i)),
                            vector ? literals.get(i) : null,vector ? fingerprint : null);
                    jdbc.update("""
                            UPDATE staff_knowledge_documents SET index_status='READY',index_error=?,chunk_count=?,updated_at=now() WHERE id=?
                            """, vector ? null : "仅全文检索：本地 bge-m3 向量索引未配置，配置后可重新索引",chunks.size(),p.id());
                    return true;
                });
                if (Boolean.TRUE.equals(saved)) indexed++;
            } catch (RuntimeException ex) {
                jdbc.update("""
                        UPDATE staff_knowledge_documents SET index_status=?,index_error=?,updated_at=now()
                        WHERE id=? AND revision=? AND index_attempts=? AND index_status='INDEXING'
                        """, p.attempt() >= 3 ? "FAILED" : "QUEUED",
                        p.attempt() >= 3 ? "索引暂时失败，请点击重新索引重试" : "索引暂时失败，稍后自动重试",
                        p.id(),p.revision(),p.attempt());
            }
        }
        return indexed;
    }
    static List<String> chunks(String content) {
        int[] points = content.codePoints().toArray();
        var chunks = new ArrayList<String>();
        for (int offset=0;offset<points.length;offset+=1650) {
            int length=Math.min(1800,points.length-offset);
            chunks.add(new String(points,offset,length));
            if (offset+length==points.length) break;
        }
        return List.copyOf(chunks);
    }
}
