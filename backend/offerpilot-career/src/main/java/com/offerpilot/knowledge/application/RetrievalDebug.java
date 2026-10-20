package com.offerpilot.knowledge.application;

import com.offerpilot.common.error.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Public occupations only. Debug reranking is deterministic term coverage, never a model score. */
@Service
public class RetrievalDebug {
    private final JdbcTemplate jdbc;
    private final OccupationEmbeddings embeddings;
    public RetrievalDebug(JdbcTemplate jdbc, OccupationEmbeddings embeddings) { this.jdbc = jdbc; this.embeddings = embeddings; }
    public record Candidate(String id, String name, String excerpt, Double lexicalScore, Double vectorScore) {}
    public record Hit(String id, String name, String excerpt, Integer lexicalRank, Integer vectorRank,
                      Double lexicalScore, Double vectorScore, double recallScore, double rerankScore, int recallRank, int rank) {}
    public record Result(List<Hit> items, boolean vectorAvailable, String embedding, String reranker, String notice) {}
    public Result search(String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 2000 || limit < 1 || limit > 50)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "搜索文字 1-2000 字，limit 1-50");
        int count = Math.min(200, Math.max(20, limit * 4));
        String q = OccupationDocument.lexicalQuery(query);
        var lexical = q.isBlank() ? List.<Candidate>of() : jdbc.query("""
                SELECT id,name,description,ts_rank_cd(tsv,q) AS score FROM occupations,to_tsquery('simple',?) q
                WHERE tsv @@ q ORDER BY score DESC,id LIMIT ?
                """, (rs,n) -> new Candidate(rs.getString(1),rs.getString(2),rs.getString(3),rs.getDouble(4),null), q, count);
        List<Candidate> vector = List.of();
        boolean available = false;
        // Never send a debug query to an external embedding supplier or mix existing index spaces.
        String fingerprint = embeddings.fingerprint();
        boolean local = fingerprint.equals("bge-m3@local") || fingerprint.equals("bge-m3:latest@local");
        var indexed = local ? jdbc.queryForList("SELECT fingerprint FROM embedding_index_state WHERE id=1", String.class) : List.<String>of();
        if (local && indexed.contains(fingerprint) && embeddings.configured()) {
            var values = embeddings.embed(List.of(query.strip()));
            if (!values.isEmpty()) {
                vector = jdbc.query("""
                        SELECT id,name,description,1-(embedding <=> CAST(? AS vector)) AS score FROM occupations
                        WHERE embedding IS NOT NULL ORDER BY embedding <=> CAST(? AS vector),id LIMIT ?
                        """, (rs,n) -> new Candidate(rs.getString(1),rs.getString(2),rs.getString(3),null,rs.getDouble(4)),
                        OccupationEmbeddings.literal(values.get(0)), OccupationEmbeddings.literal(values.get(0)), count);
                available = !vector.isEmpty();
            }
        }
        return rank(query, limit, lexical, vector, available);
    }
    static Result rank(String query, int limit, List<Candidate> lexical, List<Candidate> vector, boolean available) {
        var scores = OccupationRrf.weighted(new double[]{1.0, available ? OccupationRrf.LEXICAL_WITH_VECTOR : 1.0},
                vector.stream().map(Candidate::id).toList(), lexical.stream().map(Candidate::id).toList());
        var merged = new LinkedHashMap<String,Candidate>();
        vector.forEach(hit -> merged.put(hit.id(), hit));
        lexical.forEach(hit -> merged.merge(hit.id(), hit, (v,l) -> new Candidate(l.id(),l.name(),l.excerpt(),l.lexicalScore(),v.vectorScore())));
        var recall = merged.values().stream().sorted(Comparator.<Candidate>comparingDouble(hit -> scores.get(hit.id())).reversed()
                .thenComparing(Candidate::id)).toList();
        var terms = OccupationDocument.lexicalTokens(query).stream().distinct().toList();
        List<Hit> reranked = new ArrayList<>();
        for (int i=0;i<recall.size();i++) {
            var hit=recall.get(i);
            String text=(hit.name()+" "+hit.excerpt()).toLowerCase(Locale.ROOT);
            double coverage=terms.isEmpty()?0:terms.stream().filter(text::contains).count()/(double)terms.size();
            reranked.add(new Hit(hit.id(),hit.name(),KnowledgeNoteSearch.bounded(hit.excerpt(),1000),
                    rankOf(lexical,hit.id()),rankOf(vector,hit.id()),hit.lexicalScore(),hit.vectorScore(),scores.get(hit.id()),coverage,i+1,0));
        }
        var ordered=reranked.stream().sorted(Comparator.comparingDouble(Hit::rerankScore).reversed()
                .thenComparingInt(Hit::recallRank)).limit(limit).toList();
        List<Hit> items=new ArrayList<>();
        for(int i=0;i<ordered.size();i++) {
            var h=ordered.get(i); items.add(new Hit(h.id(),h.name(),h.excerpt(),h.lexicalRank(),h.vectorRank(),h.lexicalScore(),h.vectorScore(),h.recallScore(),h.rerankScore(),h.recallRank(),i+1));
        }
        return new Result(List.copyOf(items),available,"本地 bge-m3 / 1024 维","query-term-coverage-v1",
                available ? "公开职业库；RRF 召回后按查询词覆盖率重排，非模型置信度。" : "本地 bge-m3 或同空间向量索引不可用，当前仅全文召回；按查询词覆盖率重排，非模型置信度。");
    }
    private static Integer rankOf(List<Candidate> hits,String id) {
        for(int i=0;i<hits.size();i++) if(hits.get(i).id().equals(id)) return i+1;
        return null;
    }
}
