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
import java.util.stream.Collectors;

/** FTS and exact vectors both filter by owner BEFORE ranking; optional embeddings never gate FTS. */
@Service
public class KnowledgeNoteSearch {
    private final JdbcTemplate jdbc;
    private final OccupationEmbeddings embeddings;
    public KnowledgeNoteSearch(JdbcTemplate jdbc, OccupationEmbeddings embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }
    public record Hit(String id, String title, String category, String excerpt, String updatedAt,
                      double score, boolean lexicalMatch, boolean vectorMatch) {}
    public record Result(List<Hit> items, boolean vectorAvailable) {}

    public Result search(String owner, String query, int limit) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 2000 || limit < 1 || limit > 50)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "搜索文字 1-2000 字，limit 1-50");
        int candidates = Math.min(200, Math.max(20, limit * 4));
        String q = OccupationDocument.lexicalQuery(query.strip());
        var lexical = q.isEmpty() ? List.<Hit>of() : jdbc.query("""
                SELECT id,title,category,content,updated_at FROM knowledge_notes,to_tsquery('simple',?) q
                WHERE user_id=? AND tsv @@ q ORDER BY ts_rank_cd(tsv,q) DESC,id LIMIT ?
                """, KnowledgeNoteSearch::map, q, owner, candidates);
        List<Hit> vector = List.of();
        boolean available = false;
        if (embeddings.configured() && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM knowledge_notes WHERE user_id=? AND embedding IS NOT NULL)", Boolean.class, owner))) {
            var embedded = embeddings.embed(List.of(query.strip()));
            if (!embedded.isEmpty()) {
                vector = jdbc.query("""
                        SELECT id,title,category,content,updated_at FROM knowledge_notes
                        WHERE user_id=? AND embedding IS NOT NULL
                        ORDER BY embedding <=> CAST(? AS vector),id LIMIT ?
                        """, KnowledgeNoteSearch::map, owner, OccupationEmbeddings.literal(embedded.get(0)), candidates);
                available = true;
            }
        }
        var scores = OccupationRrf.scores(lexical.stream().map(Hit::id).toList(), vector.stream().map(Hit::id).toList());
        var merged = new LinkedHashMap<String, Hit>();
        lexical.forEach(hit -> merged.put(hit.id(), hit));
        vector.forEach(hit -> merged.put(hit.id(), hit));
        var lexicalIds = lexical.stream().map(Hit::id).collect(Collectors.toSet());
        var vectorIds = vector.stream().map(Hit::id).collect(Collectors.toSet());
        return new Result(merged.values().stream().map(hit -> new Hit(hit.id(), hit.title(), hit.category(), hit.excerpt(),
                hit.updatedAt(), scores.get(hit.id()), lexicalIds.contains(hit.id()), vectorIds.contains(hit.id())))
                .sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id)).limit(limit).toList(), available);
    }

    public static String bounded(String value, int points) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(points, value.codePointCount(0, value.length()))));
    }
    private static Hit map(ResultSet rs, int row) throws SQLException {
        return new Hit(rs.getString("id"), rs.getString("title"), rs.getString("category"), bounded(rs.getString("content"), 1000),
                rs.getTimestamp("updated_at").toInstant().toString(), 0, false, false);
    }
}
