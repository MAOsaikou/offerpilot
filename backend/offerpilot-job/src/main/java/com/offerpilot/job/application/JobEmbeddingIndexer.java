package com.offerpilot.job.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

/** Index only public real jobs; payload hash prevents late vectors replacing changed JD. */
@Component
public class JobEmbeddingIndexer {
    private final JdbcTemplate jdbc; private final JobEmbeddings embeddings;
    public JobEmbeddingIndexer(JdbcTemplate jdbc,JobEmbeddings embeddings) { this.jdbc=jdbc; this.embeddings=embeddings; }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void index() {
        if(!embeddings.enabled()) return;
        var rows=jdbc.queryForList("SELECT id,payload::text AS payload,md5(payload::text) AS hash FROM jobs WHERE user_id IS NULL AND coalesce(payload->>'source','') NOT IN ('demo','LOCAL_SNAPSHOT') AND (search_embedding IS NULL OR embedding_key IS DISTINCT FROM ? OR embedding_hash IS DISTINCT FROM md5(payload::text)) ORDER BY updated_at DESC LIMIT 10",embeddings.key());
        for(var row:rows) {
            String vector=embeddings.embed(row.get("payload").toString());
            if(vector==null) break;
            jdbc.update("UPDATE jobs SET search_embedding=?::vector,embedding_key=?,embedding_hash=? WHERE id=? AND md5(payload::text)=?",vector,embeddings.key(),row.get("hash"),row.get("id"),row.get("hash"));
        }
    }
}
