package com.offerpilot.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Database-backed retry queue; saves only write pending rows. Provider calls never hold a transaction. */
@Component
public class KnowledgeNoteEmbeddingJob {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeNoteEmbeddingJob.class);
    private final JdbcTemplate jdbc;
    private final OccupationEmbeddings embeddings;
    private final boolean enabled;
    public KnowledgeNoteEmbeddingJob(JdbcTemplate jdbc, OccupationEmbeddings embeddings,
            @Value("${offerpilot.knowledge.notes-embed.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.enabled = enabled;
    }
    private record Entry(String id, String owner, long revision, int attempt, String document) {}

    @Scheduled(fixedDelayString = "${offerpilot.knowledge.notes-embed.delay-ms:30000}", initialDelayString = "${offerpilot.knowledge.notes-embed.delay-ms:30000}")
    public void tick() {
        try { indexPending(); }
        catch (RuntimeException ex) {
            // Do not log supplier errors or documents: they may contain credentials/private text.
            log.warn("Note indexing deferred ({})", ex.getClass().getSimpleName());
        }
    }

    public int indexPending() {
        // Disabled or no embedding provider: leave rows alone. Create/update already set READY when
        // vectors are unavailable; never rewrite a concurrent INDEXING lease.
        if (!enabled || !embeddings.configured()) return 0;
        // An abandoned final lease must also reach a terminal status.
        jdbc.update("""
                UPDATE knowledge_notes SET index_status='FAILED',index_error='向量索引暂时失败，全文检索仍可用；保存笔记可重试'
                WHERE embedding IS NULL AND embedding_attempts>=3 AND embedding_next_attempt_at<=now() AND index_status<>'FAILED'
                """);
        var entries = jdbc.query("""
                WITH pending AS (
                    SELECT id FROM knowledge_notes WHERE embedding IS NULL AND embedding_next_attempt_at<=now() AND embedding_attempts<3 AND index_status<>'FAILED'
                    ORDER BY embedding_next_attempt_at,id LIMIT 8 FOR UPDATE SKIP LOCKED
                )
                UPDATE knowledge_notes n SET embedding_next_attempt_at=now()+interval '5 minutes',
                    index_status='INDEXING',index_error=NULL,embedding_attempts=embedding_attempts+1
                FROM pending p WHERE n.id=p.id RETURNING n.id,n.user_id,n.revision,n.embedding_attempts,n.title,n.content,n.category
                """, (rs, row) -> new Entry(rs.getString("id"), rs.getString("user_id"), rs.getLong("revision"), rs.getInt("embedding_attempts"),
                KnowledgeNoteSearch.bounded(KnowledgeNoteService.document(new KnowledgeNoteService.Draft(
                        rs.getString("title"), rs.getString("content"), rs.getString("category"))), 6000)));
        if (entries.isEmpty()) return 0;
        int indexed = 0;
        var groups = entries.stream().collect(java.util.stream.Collectors.groupingBy(Entry::owner));
        for (var group : groups.values()) {
            var vectors = com.offerpilot.llm.quota.LlmUserContext.asUser(group.get(0).owner(),
                    () -> embeddings.embed(group.stream().map(Entry::document).toList()));
            if (vectors.isEmpty()) {
                for (Entry entry : group) jdbc.update("""
                        UPDATE knowledge_notes SET index_status=?,index_error=?
                        WHERE id=? AND revision=? AND embedding_attempts=? AND embedding IS NULL
                        """, entry.attempt() >= 3 ? "FAILED" : "QUEUED",
                        entry.attempt() >= 3 ? "向量索引暂时失败，全文检索仍可用；保存笔记可重试" : "向量索引调用失败，稍后自动重试；全文检索仍可用",
                        entry.id(), entry.revision(), entry.attempt());
                continue;
            }
            for (int i = 0; i < group.size(); i++) {
                Entry entry = group.get(i);
                indexed += jdbc.update("""
                        UPDATE knowledge_notes SET embedding=CAST(? AS vector),index_status='READY',index_error=NULL
                        WHERE id=? AND revision=? AND embedding_attempts=? AND embedding IS NULL
                        """, OccupationEmbeddings.literal(vectors.get(i)), entry.id(), entry.revision(), entry.attempt());
            }
        }
        return indexed;
    }
}
