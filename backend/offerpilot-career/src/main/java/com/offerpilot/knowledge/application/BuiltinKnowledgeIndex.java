package com.offerpilot.knowledge.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

/** Shares canonical tokenization and local embedding space. No external embedding is permitted. */
@Component
@Order(21)
public class BuiltinKnowledgeIndex implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final OccupationEmbeddings embeddings;
    public BuiltinKnowledgeIndex(JdbcTemplate jdbc,OccupationEmbeddings embeddings) { this.jdbc=jdbc;this.embeddings=embeddings; }
    public static boolean local(String fingerprint) { return "bge-m3@local".equals(fingerprint)||"bge-m3:latest@local".equals(fingerprint); }
    public record Pending(String id,long revision,String document) {}
    @Override public void run(ApplicationArguments args) { reindex(); }
    @Scheduled(fixedDelayString="${offerpilot.knowledge.builtin-index-delay-ms:60000}")
    public synchronized int reindex() {
        String fingerprint=embeddings.fingerprint();
        if(!local(fingerprint)||!embeddings.configured()) return 0;
        var pending=jdbc.query("""
            SELECT id,revision,title,content,industry,role FROM builtin_knowledge_entries
            WHERE status='READY' AND (embedding IS NULL OR embedding_fingerprint IS DISTINCT FROM ?)
            ORDER BY id LIMIT 20
            """,(r,n) -> new Pending(r.getString(1),r.getLong(2),document(r.getString(3),r.getString(4),r.getString(5),r.getString(6))),fingerprint);
        if(pending.isEmpty()) return 0;
        List<float[]> vectors=embeddings.embed(pending.stream().map(Pending::document).toList());
        if(vectors.size()!=pending.size() || !fingerprint.equals(embeddings.fingerprint())) return 0;
        int count=0;
        for(int i=0;i<pending.size();i++) {
            var p=pending.get(i);
            count+=jdbc.update("UPDATE builtin_knowledge_entries SET embedding=?::vector,embedding_fingerprint=? WHERE id=? AND revision=?",OccupationEmbeddings.literal(vectors.get(i)),fingerprint,p.id(),p.revision());
        }
        return count;
    }
    public static String document(String title,String content,String industry,String role) { return title+"\n"+content+"\n"+industry+"\n"+role; }
}
