package com.offerpilot.career.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.knowledge.application.OccupationHybridRetriever;
import com.offerpilot.knowledge.application.OccupationDocument;
import com.offerpilot.knowledge.application.OccupationRrf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;

/**
 * 规则层的职业推荐：按霍兰德兴趣码给 occupations 表打分并排序（docs/04 §2.3 的规则层）。
 *
 * rank 保留位置加权相似度；rankHybrid 可选与职业检索结果做 RRF 融合。
 * 当前职业库是数百条量级，全表取出在内存里打分足够快；数据量再大时应先按
 * holland_code 精确命中构造候选集，再对候选集打分。
 */
@Service
public class OccupationRecommender {
    private static final int MAX_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(OccupationRecommender.class);
    private final JdbcTemplate jdbc;
    private final OccupationHybridRetriever retriever;

    public OccupationRecommender(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public OccupationRecommender(JdbcTemplate jdbc, OccupationHybridRetriever retriever) {
        this.jdbc = jdbc;
        this.retriever = retriever;
    }

    public record Ranked(String id, String name, String family, String hollandCode, double similarity) {
    }

    public record Citation(String occupationId, String title, String excerpt) {}

    public List<Citation> citations(String occupationId) {
        return jdbc.query("SELECT id,name,description,core_skills FROM occupations WHERE id=?",
                (rs, row) -> new Citation(rs.getString("id"), rs.getString("name"),
                        OccupationDocument.excerpt(rs.getString("description"), rs.getString("core_skills"))), occupationId);
    }

    public List<OccupationHybridRetriever.Hit> retrieve(String query, int limit) {
        OccupationHybridRetriever.validateLimit(limit);
        return retriever == null ? List.of() : retriever.retrieve(query, limit);
    }

    /** RRF changes order only: similarity remains the original Holland score for card explanations. */
    public List<Ranked> rankHybrid(String hollandCode, String query, int limit) {
        OccupationHybridRetriever.validateLimit(limit);
        var rules = rankContext(hollandCode, query, Math.min(MAX_LIMIT, limit * 2));
        if (retriever == null || query == null || query.isBlank()) return rules.stream().limit(limit).toList();
        try {
            var retrieval = retriever.search(query, Math.min(MAX_LIMIT, limit * 2));
            // Without vectors, use deterministic Holland + explicit field relevance rules.
            if (!retrieval.vectorAvailable() || retrieval.hits().isEmpty()) return rules.stream().limit(limit).toList();
            var candidates = new LinkedHashMap<String, Ranked>();
            rules.forEach(item -> candidates.put(item.id(), item));
            retrieval.hits().stream().filter(item -> item.hollandCode() != null).forEach(item ->
                    candidates.putIfAbsent(item.id(), new Ranked(item.id(), item.name(), item.family(), item.hollandCode(),
                            HollandMatcher.score(HollandMatcher.normalize(hollandCode), item.hollandCode()))));
            var scores = OccupationRrf.scores(rules.stream().map(Ranked::id).toList(),
                    retrieval.hits().stream().map(OccupationHybridRetriever.Hit::id).toList());
            return candidates.values().stream().filter(item -> item.similarity() > 0)
                    .sorted(Comparator.<Ranked>comparingDouble(item -> scores.get(item.id())).reversed()
                            .thenComparing(Ranked::name).thenComparing(Ranked::id)).limit(limit).toList();
        } catch (Exception ex) {
            log.warn("职业混合检索失败 ({})；回退 Holland 规则", ex.getClass().getSimpleName());
            return rules.stream().limit(limit).toList();
        }
    }

    /** Similarity stays the raw Holland score; field relevance affects order, never the displayed score. */
    public List<Ranked> rankContext(String hollandCode, String query, int limit) {
        String code = HollandMatcher.normalize(hollandCode);
        if (!code.matches("[RIASEC]{1,3}")) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        double maximum = switch (HollandMatcher.normalize(hollandCode).length()) { case 1 -> 3; case 2 -> 5; default -> 6; };
        if (limit < 1 || limit > MAX_LIMIT) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        // Query all positive matches before limiting: broad interests must not exclude the student's field.
        var candidates = allMatches(code);
        return candidates.stream().sorted(Comparator.<Ranked>comparingDouble(item ->
                        0.45 * item.similarity() / maximum + 0.55 * OccupationRelevance.score(query, item)).reversed()
                .thenComparing(Ranked::name).thenComparing(Ranked::id)).limit(limit).toList();
    }

    public Ranked find(String occupationId, String name) {
        if ((occupationId == null || occupationId.isBlank()) && (name == null || name.isBlank()))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择目标职业");
        var rows = jdbc.query("SELECT id,name,family,holland_code FROM occupations WHERE "
                        + (occupationId != null ? "id=?" : "name=?"),
                (rs, row) -> new Ranked(rs.getString("id"), rs.getString("name"), rs.getString("family"),
                        rs.getString("holland_code"), 0), occupationId != null ? occupationId : name);
        if (rows.size() != 1 || name != null && !name.equals(rows.get(0).name()))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标职业不存在或名称与标识不一致");
        return rows.get(0);
    }

    /**
     * @param hollandCode 用户的霍兰德兴趣码，1-3 个 RIASEC 字母（大小写与分隔符不敏感）
     * @return 按相似度降序排列的职业；相似度为 0 的不返回
     */
    public List<Ranked> rank(String hollandCode, int limit) {
        String code = HollandMatcher.normalize(hollandCode);
        if (!code.matches("[RIASEC]{1,3}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "兴趣码必须是 1-3 个 RIASEC 字母");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit 取值范围为 1-" + MAX_LIMIT);
        }

        return allMatches(code).stream()
                .sorted(Comparator.comparingDouble(Ranked::similarity).reversed()
                        .thenComparing(Ranked::name).thenComparing(Ranked::id))
                .limit(limit).toList();
    }

    private List<Ranked> allMatches(String code) {
        return jdbc.query("SELECT id,name,family,holland_code FROM occupations WHERE holland_code IS NOT NULL",
                        (rs, row) -> new Ranked(
                                rs.getString("id"),
                                rs.getString("name"),
                                rs.getString("family"),
                                rs.getString("holland_code"),
                                HollandMatcher.score(code, rs.getString("holland_code"))))
                .stream()
                .filter(ranked -> ranked.similarity() > 0)
                .toList();
    }
}
