package com.offerpilot.career.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.knowledge.application.OccupationHybridRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.knowledge.application.OccupationRrf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;

/** Interest-profile ranking with optional occupation-only lexical/vector RRF retrieval. */
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

    public record Ranked(String id, String name, String family, String hollandCode, double similarity,
                         List<Citation> citations, String retrievalReason) {
        public Ranked(String id, String name, String family, String hollandCode, double similarity) {
            this(id, name, family, hollandCode, similarity, List.of(), "");
        }
    }

    public record Citation(String occupationId, String title, String excerpt) {}

    public List<OccupationHybridRetriever.Hit> retrieve(String query, int limit) {
        OccupationHybridRetriever.validateLimit(limit);
        return retriever == null ? List.of() : retriever.retrieve(query, limit);
    }

    public List<Ranked> rank(RiasecProfile profile, int limit) {
        OccupationHybridRetriever.validateLimit(limit);
        return profileMatches(profile).stream().limit(limit).toList();
    }

    public List<Ranked> rankHybrid(RiasecProfile profile, String query, int limit) {
        OccupationHybridRetriever.validateLimit(limit);
        return fuse(profileMatches(profile), query, limit);
    }

    /** Compatibility for code-only callers; chat always passes the original six-dimensional profile. */
    public List<Ranked> rankHybrid(String hollandCode, String query, int limit) {
        OccupationHybridRetriever.validateLimit(limit);
        String code = HollandMatcher.normalize(hollandCode);
        if (!code.matches("[RIASEC]{1,3}"))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "兴趣码必须是 1-3 个 RIASEC 字母");
        var rules = allMatches(code).stream()
                .sorted(Comparator.comparingDouble(Ranked::similarity).reversed()
                        .thenComparing(Ranked::name).thenComparing(Ranked::id)).toList();
        return fuse(rules, query, limit);
    }

    private List<Ranked> fuse(List<Ranked> rules, String query, int limit) {
        if (retriever == null || query == null || query.isBlank()) return rules.stream().limit(limit).toList();
        try {
            var retrieval = retriever.search(query, Math.min(MAX_LIMIT, limit * 2));
            // Lexical retrieval is valid evidence even without embeddings. Keep the full profile
            // list until fusion: an interest-only top-K would discard relevant text candidates.
            if (retrieval.hits().isEmpty()) return rules.stream().limit(limit).toList();
            var hits = new LinkedHashMap<String, OccupationHybridRetriever.Hit>();
            retrieval.hits().forEach(item -> hits.put(item.id(), item));
            var byId = new LinkedHashMap<String, Ranked>();
            rules.forEach(item -> byId.put(item.id(), item));
            // Re-rank only the retrieved candidates. The interest prior is ranked within that short list:
            // a full-catalog RRF list let distant prior gaps outweigh the user's own words (live eval).
            var retrieved = retrieval.hits().stream().map(OccupationHybridRetriever.Hit::id)
                    .filter(byId::containsKey).toList();
            var byProfile = retrieved.stream().sorted(Comparator.comparingInt(
                    id -> rules.indexOf(byId.get(id)))).toList();
            var scores = OccupationRrf.weighted(
                    new double[]{1.0, retrieval.vectorAvailable() ? OccupationRrf.PROFILE_WITH_VECTOR : 1.0},
                    retrieved, byProfile);
            var ordered = new java.util.ArrayList<Ranked>(retrieved.stream().map(byId::get)
                    .sorted(Comparator.<Ranked>comparingDouble(item -> scores.get(item.id())).reversed()
                            .thenComparing(Ranked::name).thenComparing(Ranked::id)).toList());
            rules.stream().filter(item -> !hits.containsKey(item.id())).forEach(ordered::add);
            return ordered.stream().limit(limit).map(item -> {
                        var hit = hits.get(item.id());
                        if (hit == null) return item;
                        String reason = hit.vectorMatch()
                                ? "与你的描述/目标及已保存兴趣、技能在职业库语义检索中匹配，并按兴趣画像重排"
                                : "与你的描述/目标及已保存兴趣、技能在职业库文本中匹配，并按兴趣画像重排";
                        return new Ranked(item.id(), item.name(), item.family(), item.hollandCode(), item.similarity(),
                                List.of(new Citation(hit.id(), hit.name(), hit.excerpt())), reason);
                    }).toList();
        } catch (Exception ex) {
            log.warn("职业混合检索失败 ({})；回退 RIASEC 兴趣排序", ex.getClass().getSimpleName());
            return rules.stream().limit(limit).toList();
        }
    }

    private List<Ranked> profileMatches(RiasecProfile profile) {
        var json = new ObjectMapper();
        return jdbc.query("SELECT id,name,family,holland_code,holland_scores FROM occupations WHERE holland_scores IS NOT NULL",
                (rs, row) -> {
                    try {
                        var occupation = RiasecProfile.fromJson(json.readTree(rs.getString("holland_scores")));
                        // Preserve the existing card's 0..1 numeric contract; explanations expose r.
                        double similarity = (profile.correlation(occupation) + 1) / 2;
                        return new Ranked(rs.getString("id"), rs.getString("name"), rs.getString("family"),
                                rs.getString("holland_code"), similarity);
                    } catch (Exception ex) {
                        throw new IllegalStateException("Invalid occupation RIASEC profile: " + rs.getString("id"), ex);
                    }
                }).stream().sorted(Comparator.comparingDouble(Ranked::similarity).reversed()
                        .thenComparing(Ranked::name).thenComparing(Ranked::id)).toList();
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
