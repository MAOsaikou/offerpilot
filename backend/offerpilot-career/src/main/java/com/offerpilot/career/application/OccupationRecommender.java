package com.offerpilot.career.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 规则层的职业推荐：按霍兰德兴趣码给 occupations 表打分并排序（docs/04 §2.3 的规则层）。
 *
 * 只做位置加权相似度，不做向量检索——检索层要在 RAG 接入后与本层结果做 RRF 融合。
 * 当前职业库是数百条量级，全表取出在内存里打分足够快；数据量再大时应先按
 * holland_code 精确命中构造候选集，再对候选集打分。
 */
@Service
public class OccupationRecommender {
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;

    public OccupationRecommender(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Ranked(String id, String name, String family, String hollandCode, double similarity) {
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

        return jdbc.query("SELECT id,name,family,holland_code FROM occupations WHERE holland_code IS NOT NULL",
                        (rs, row) -> new Ranked(
                                rs.getString("id"),
                                rs.getString("name"),
                                rs.getString("family"),
                                rs.getString("holland_code"),
                                HollandMatcher.score(code, rs.getString("holland_code"))))
                .stream()
                .filter(ranked -> ranked.similarity() > 0)
                .sorted(Comparator.comparingDouble(Ranked::similarity).reversed()
                        .thenComparing(Ranked::name))   // 同分时按名称排序，保证结果可复现
                .limit(limit)
                .toList();
    }
}
