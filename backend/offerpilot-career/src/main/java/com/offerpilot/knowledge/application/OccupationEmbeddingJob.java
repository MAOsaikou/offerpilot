package com.offerpilot.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Re-runnable occupation-only indexing, after JSON seed. Provider calls hold no DB transaction. */
@Component
@Order(20)
public class OccupationEmbeddingJob implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OccupationEmbeddingJob.class);
    private static final int BATCH_SIZE = 16;
    private final JdbcTemplate jdbc;
    private final OccupationEmbeddings embeddings;
    private final boolean enabled;
    private final boolean force;

    public OccupationEmbeddingJob(JdbcTemplate jdbc, OccupationEmbeddings embeddings,
            @Value("${offerpilot.knowledge.occupation-embed.enabled:true}") boolean enabled,
            @Value("${offerpilot.knowledge.occupation-embed.force:false}") boolean force) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.enabled = enabled;
        this.force = force;
    }

    @Override public void run(ApplicationArguments args) { reindex(force); }

    public record Result(int fulltextUpdated, int embedded) {}

    /** Vectors from another model are not comparable with new query vectors: drop them all once.
     * Runs under reindex's monitor; a crash before the state upsert only repeats the reset. */
    public void resetIfModelChanged() {
        if (!embeddings.configured()) return;
        String current = embeddings.fingerprint();
        var stored = jdbc.queryForList("SELECT fingerprint FROM embedding_index_state WHERE id=1", String.class);
        if (!stored.isEmpty() && stored.get(0).equals(current)) return;
        int occupations = jdbc.update("UPDATE occupations SET embedding=NULL WHERE embedding IS NOT NULL");
        int notes = jdbc.update("UPDATE knowledge_notes SET embedding=NULL,embedding_next_attempt_at=now() WHERE embedding IS NOT NULL");
        jdbc.update("""
                INSERT INTO embedding_index_state(id,fingerprint) VALUES (1,?)
                ON CONFLICT (id) DO UPDATE SET fingerprint=EXCLUDED.fingerprint,updated_at=now()
                """, current);
        if (occupations + notes > 0)
            log.info("向量模型已切换为 {}：清空旧向量 职业 {} 条、笔记 {} 条，将重新索引", current, occupations, notes);
    }
    private record Entry(String id, String document, OffsetDateTime version) {}

    public synchronized Result reindex(boolean forceAll) {
        resetIfModelChanged();
        var entries = jdbc.query("SELECT * FROM occupations ORDER BY id", (rs, row) -> new Entry(
                rs.getString("id"), OccupationDocument.build(rs.getString("name"), rs.getString("description"),
                rs.getString("core_skills"), rs.getString("growth_path"), rs.getString("family"),
                (String[]) rs.getArray("typical_titles").getArray()), rs.getObject("updated_at", OffsetDateTime.class)));
        int fulltext = 0;
        List<Entry> pending = new ArrayList<>();
        for (Entry entry : entries) {
            // tsv doubles as a content-change check for edits outside the seeder. Seeder also detects
            // order/punctuation-only changes. Preserve updated_at as the source version, not index time.
            fulltext += jdbc.update("""
                    UPDATE occupations SET tsv=to_tsvector('simple', ?), embedding=NULL
                    WHERE id=? AND updated_at=? AND (tsv IS DISTINCT FROM to_tsvector('simple', ?) OR ?)
                    """, OccupationDocument.fulltext(entry.document()), entry.id(), entry.version(),
                    OccupationDocument.fulltext(entry.document()), forceAll);
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT embedding IS NULL FROM occupations WHERE id=?", Boolean.class, entry.id()))) pending.add(entry);
        }
        int embedded = 0;
        if (enabled) {
            for (int offset = 0; offset < pending.size(); offset += BATCH_SIZE) {
                var batch = pending.subList(offset, Math.min(offset + BATCH_SIZE, pending.size()));
                var vectors = embeddings.embed(batch.stream().map(Entry::document).toList());
                if (vectors.isEmpty()) break; // Avoid repeated failed provider calls this run; next run retries.
                for (int i = 0; i < batch.size(); i++) {
                    Entry entry = batch.get(i);
                    embedded += jdbc.update("""
                            UPDATE occupations SET embedding=CAST(? AS vector)
                            WHERE id=? AND updated_at=? AND embedding IS NULL
                            """, OccupationEmbeddings.literal(vectors.get(i)), entry.id(), entry.version());
                }
            }
        }
        log.info("职业索引完成：全文更新 {} 条，向量更新 {} 条", fulltext, embedded);
        return new Result(fulltext, embedded);
    }
}
