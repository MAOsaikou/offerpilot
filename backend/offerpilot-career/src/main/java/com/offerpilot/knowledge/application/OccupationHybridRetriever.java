package com.offerpilot.knowledge.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Public occupation-only retrieval hook for career callers. No kb_chunks or private corpus. */
@Service
public class OccupationHybridRetriever {
    private final JdbcTemplate jdbc;
    private final OccupationEmbeddings embeddings;

    public OccupationHybridRetriever(JdbcTemplate jdbc, OccupationEmbeddings embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    public record Hit(String id, String name, String family, String hollandCode, double rrf, String excerpt, boolean lexicalMatch, boolean vectorMatch) {}
    public record Retrieval(List<Hit> hits, boolean vectorAvailable) {}

    public List<Hit> retrieve(String query, int limit) { return search(query, limit).hits(); }

    public Retrieval search(String query, int limit) {
        validateLimit(limit);
        if (query == null || query.isBlank()) return new Retrieval(List.of(), false);
        String text = query.strip();
        if (text.length() > 2000) text = text.substring(0, 2000);
        int candidates = Math.min(200, Math.max(20, limit * 2));
        String lexicalQuery = OccupationDocument.lexicalQuery(text);
        var fts = lexicalQuery.isEmpty() ? List.<Hit>of() : jdbc.query("""
                SELECT id,name,family,holland_code,description,core_skills FROM occupations,
                to_tsquery('simple', ?) q WHERE tsv @@ q
                ORDER BY ts_rank_cd(tsv, q) DESC,name,id LIMIT ?
                """, OccupationHybridRetriever::hit, lexicalQuery, candidates);
        List<Hit> vector = List.of();
        boolean available = false;
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM occupations WHERE embedding IS NOT NULL)", Boolean.class))) {
            var embedded = embeddings.embed(List.of(text));
            if (!embedded.isEmpty()) {
                vector = jdbc.query("""
                        SELECT id,name,family,holland_code,description,core_skills FROM occupations
                        WHERE embedding IS NOT NULL ORDER BY embedding <=> CAST(? AS vector),name,id LIMIT ?
                        """, OccupationHybridRetriever::hit, OccupationEmbeddings.literal(embedded.get(0)), candidates);
                available = true;
            }
        }
        var scores = OccupationRrf.scores(vector.stream().map(Hit::id).toList(), fts.stream().map(Hit::id).toList());
        var hits = new LinkedHashMap<String, Hit>();
        vector.forEach(item -> hits.put(item.id(), item));
        fts.forEach(item -> hits.put(item.id(), item));
        var vectorIds = vector.stream().map(Hit::id).collect(java.util.stream.Collectors.toSet());
        var ranked = hits.values().stream()
                .map(item -> new Hit(item.id(), item.name(), item.family(), item.hollandCode(), scores.get(item.id()), item.excerpt(),
                        fts.stream().anyMatch(hit -> hit.id().equals(item.id())),
                        vectorIds.contains(item.id())))
                .sorted(Comparator.comparingDouble(Hit::rrf).reversed().thenComparing(Hit::name).thenComparing(Hit::id))
                .limit(limit).toList();
        return new Retrieval(ranked, available);
    }

    public static void validateLimit(int limit) {
        if (limit < 1 || limit > 200) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit 取值范围为 1-200");
    }

    private static Hit hit(ResultSet rs, int row) throws SQLException {
        return new Hit(rs.getString("id"), rs.getString("name"), rs.getString("family"),
                rs.getString("holland_code"), 0, OccupationDocument.excerpt(rs.getString("description"), rs.getString("core_skills")), false, false);
    }
}
