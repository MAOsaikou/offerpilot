package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

@Service
public class CareerRecommendationService implements CareerRerankForChat {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CareerRecommendForChat recommendations;
    private final CareerProfileService profiles;
    private static final Pattern SALARY = Pattern.compile("^(\\d+(?:\\.\\d+)?)-(\\d+(?:\\.\\d+)?)k$", Pattern.CASE_INSENSITIVE);
    public CareerRecommendationService(JdbcTemplate jdbc, ObjectMapper json, CareerRecommendForChat recommendations,
                                       CareerProfileService profiles) {
        this.jdbc = jdbc; this.json = json; this.recommendations = recommendations; this.profiles = profiles;
    }
    public record Constraints(String city, Integer minSalaryK, boolean avoidOvertime, boolean research) {
        public static Constraints empty() { return new Constraints("", null, false, false); }
        public Constraints {
            if (city == null || city.codePointCount(0, city.length()) > 100
                    || minSalaryK != null && (minSalaryK < 1 || minSalaryK > 1000)) throw invalid("约束格式无效");
            city = city.strip();
        }
    }
    public record Occupation(String occupationId, String name, String family, Map<String, Double> riasec,
                             JsonNode coreSkills, JsonNode salaryRange, String growthPath, String source,
                             Double match) {}
    public record Item(Occupation occupation, double baseScore, double score, int rank, int rankDelta,
                       String changeReason, CareerCardDetails.Details details, String baseReason) {
        public Item(Occupation occupation,double baseScore,double score,int rank,int rankDelta,String changeReason,
                    CareerCardDetails.Details details) {
            this(occupation,baseScore,score,rank,rankDelta,changeReason,details,null);
        }
        public Item(Occupation occupation,double baseScore,double score,int rank,int rankDelta,String changeReason) {
            this(occupation,baseScore,score,rank,rankDelta,changeReason,null);
        }
    }
    public record Revision(String id, String parentId, Constraints constraints, List<Item> items,
                           long elapsedMs, boolean explanationFallback, String createdAt) {}
    public record CatalogEntry(String occupationId, String name) {}
    public record Comparison(List<String> axes, List<Occupation> occupations) {}

    @Transactional(readOnly = true)
    public List<CatalogEntry> catalog() {
        return jdbc.query("SELECT id,name FROM occupations WHERE holland_scores IS NOT NULL ORDER BY name,id",
                (rs, n) -> new CatalogEntry(rs.getString(1), rs.getString(2)));
    }
    @Transactional(readOnly = true)
    public Comparison compare(String owner, List<String> ids) {
        if (ids == null || ids.size() < 2 || ids.size() > 3 || new HashSet<>(ids).size() != ids.size())
            throw invalid("请选择 2–3 个不同职业");
        var profile = profiles.get(owner);
        RiasecProfile interest = profile.assessment() != null ? CareerAssessmentService.profile(profile.assessment())
                : InterestHollandMapping.derive(profile.interests()).profile();
        boolean available = profile.assessment() != null || !InterestHollandMapping.derive(profile.interests()).code().isEmpty();
        return new Comparison(List.of("R", "I", "A", "S", "E", "C"), ids.stream().map(id -> {
            var occupation = occupation(id, null);
            double similarity = (interest.correlation(RiasecProfile.fromJson(json.valueToTree(occupation.riasec())) ) + 1) / 2;
            return new Occupation(occupation.occupationId(), occupation.name(), occupation.family(), occupation.riasec(),
                    occupation.coreSkills(), occupation.salaryRange(), occupation.growthPath(), occupation.source(),
                    available ? similarity : null);
        }).toList());
    }
    private Occupation occupation(String id, Double match) {
        if (id == null || id.length() > 80) throw invalid("职业标识无效");
        var rows = jdbc.query("SELECT * FROM occupations WHERE id=? AND holland_scores IS NOT NULL", (rs, n) -> {
            try {
                var scores = json.readTree(rs.getString("holland_scores"));
                Map<String, Double> dimensions = new LinkedHashMap<>();
                for (char axis : "RIASEC".toCharArray()) {
                    double value = scores.path(String.valueOf(axis)).asDouble(Double.NaN);
                    if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalStateException("Invalid catalog dimension");
                    dimensions.put(String.valueOf(axis), value);
                }
                return new Occupation(rs.getString("id"), rs.getString("name"), rs.getString("family"), dimensions,
                        json.readTree(rs.getString("core_skills")), json.readTree(rs.getString("salary_range") == null
                        ? "null" : rs.getString("salary_range")), rs.getString("growth_path"), rs.getString("source"), match);
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        }, id);
        if (rows.isEmpty()) throw invalid("职业不存在");
        return rows.get(0);
    }
    @Transactional(readOnly = true)
    public Revision latest(String owner) {
        var ids = jdbc.query("SELECT id FROM career_recommendation_revisions WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                (rs, n) -> rs.getString(1), owner);
        return ids.isEmpty() ? null : revision(owner, ids.get(0));
    }
    @Override @Transactional(readOnly = true)
    public Revision revision(String owner, String id) {
        var rows = jdbc.query("SELECT snapshot FROM career_recommendation_revisions WHERE user_id=? AND id=?",
                (rs, n) -> {
                    try { return json.readValue(rs.getString(1), Revision.class); }
                    catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
                }, owner, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.ROUTE_NOT_FOUND, "推荐修订不存在或无权访问");
        return rows.get(0);
    }
    // Initial retrieval can call embeddings; only the final single INSERT uses an autocommit transaction.
    public Revision create(String owner) {
        long start = System.nanoTime();
        var result = recommendations.recommend(owner);
        if (result.payload() == null || result.payload().recommendations().isEmpty())
            throw invalid(result.guidance() == null ? "暂无推荐，请先补充职业画像" : result.guidance());
        var items = new ArrayList<Item>();
        for (var item : result.payload().recommendations()) {
            // New cards persist the actual fit score; old providers retain their historical rank prior.
            double prior = item.rankingScore() != null ? item.rankingScore()
                    : 1.0 - items.size() / (double) result.payload().recommendations().size();
            items.add(new Item(occupation(item.occupationId(), item.similarity()), prior, prior,
                    items.size() + 1, 0, String.join("；", item.reasons()), item.details(), String.join("；", item.reasons())));
        }
        com.offerpilot.common.artifact.BackgroundWork.check();
        return save(owner, new Revision(UUID.randomUUID().toString(), null, Constraints.empty(), items,
                (System.nanoTime() - start) / 1_000_000, false, java.time.Instant.now().toString()));
    }
    @Override @Transactional(timeout = 7)
    public Revision rerank(String owner, String parentId, Constraints constraints) {
        long start = System.nanoTime();
        // No retrieval, embedding or model calls. Bound every SQL wait as well as the transaction.
        jdbc.execute("SET LOCAL statement_timeout = '1500ms'");
        jdbc.execute("SET LOCAL lock_timeout = '500ms'");
        var parent = revision(owner, parentId);
        var result = rescore(parent, constraints, start, System::nanoTime);
        if (System.nanoTime() - start >= 6_500_000_000L)
            throw new BusinessException(ErrorCode.TOOL_FAILED, "重排预算已耗尽，请重试");
        return save(owner, result);
    }
    /** Shared deterministic scoring; the clock lets tests exercise explanation budget exhaustion without sleeping. */
    static Revision rescore(Revision parent, Constraints constraints, long start, LongSupplier clock) {
        if (constraints == null) throw invalid("请填写约束");
        var scores = new HashMap<String, Double>();
        for (Item item : parent.items()) {
            double score = item.baseScore();
            if (constraints.research()) score += .25 * item.occupation().riasec().get("I");
            score += salaryAdjustment(item.occupation(), constraints);
            scores.put(item.occupation().occupationId(), score);
        }
        var sorted = parent.items().stream().sorted(Comparator.<Item>comparingDouble(i -> scores.get(i.occupation().occupationId()))
                .reversed().thenComparing(i -> i.occupation().name()).thenComparing(i -> i.occupation().occupationId())).toList();
        boolean fallback = clock.getAsLong() - start >= 1_000_000_000L;
        var items = new ArrayList<Item>();
        for (Item item : sorted) {
            int rank = items.size() + 1;
            String reason = reason(item, constraints, parent.constraints(), fallback);
            items.add(new Item(item.occupation(), item.baseScore(), scores.get(item.occupation().occupationId()), rank,
                    item.rank() - rank, reason,item.details(), item.baseReason()));
        }
        return new Revision(UUID.randomUUID().toString(), parent.id(), constraints, items,
                Math.max(0, (clock.getAsLong() - start) / 1_000_000), fallback, java.time.Instant.now().toString());
    }
    private static double[] salary(Occupation occupation) {
        var matcher = SALARY.matcher(occupation.salaryRange().path("campus").asText(""));
        if (!matcher.matches()) return null;
        double min = Double.parseDouble(matcher.group(1)), max = Double.parseDouble(matcher.group(2));
        return max >= min ? new double[]{min, max} : null;
    }
    private static String reason(Item item, Constraints c, Constraints previous, boolean fallback) {
        var parts = new ArrayList<String>();
        // Dedicated snapshot field avoids reparsing user-controlled verbatim quotes as delimiters.
        if (item.baseReason() != null && !item.baseReason().isBlank()) parts.add(item.baseReason());
        else if (item.details() != null) item.details().evidence().forEach(e -> parts.add(e.field() + "：「" + e.value() + "」"));
        parts.add("本轮约束：基础综合分 " + item.baseScore());
        if (c.research()) parts.add("偏研究：按目录 I 研究型强度 " + item.occupation().riasec().get("I") + " × 0.25 加权");
        if (c.minSalaryK() != null) {
            double[] salary = salary(item.occupation());
            parts.add("月薪下限 " + c.minSalaryK() + "k：" + (salary == null ? "目录无可比薪资，保留基础分"
                    : "目录校招估算 " + item.occupation().salaryRange().path("campus").asText()
                    + (fallback ? "，按区间重评" : salary[0] >= c.minSalaryK() ? "，区间下界满足"
                    : salary[1] >= c.minSalaryK() ? "，区间部分满足" : "，区间上界未满足")) + "；薪资调整 " + salaryAdjustment(item.occupation(), c));
        }
        if (!c.city().isEmpty()) parts.add("城市「" + c.city() + "」：目录无城市覆盖数据，待核实，不调分");
        if (c.avoidOvertime()) parts.add("不想加班：目录无工时数据，待核实，不调分");
        if (!c.equals(previous)) parts.add("已替换上一轮约束；未启用项不再加权");
        parts.add("实际综合排序分 " + (item.baseScore() + (c.research() ? .25 * item.occupation().riasec().get("I") : 0)
                + salaryAdjustment(item.occupation(), c)) + "；同分按职业名称和标识排序");
        return String.join("；", parts);
    }
    private static double salaryAdjustment(Occupation occupation, Constraints c) {
        double[] salary = salary(occupation);
        return c.minSalaryK() == null || salary == null ? 0
                : salary[0] >= c.minSalaryK() ? .2 : salary[1] >= c.minSalaryK() ? .1 : -.2;
    }
    private Revision save(String owner, Revision revision) {
        try {
            jdbc.update("INSERT INTO career_recommendation_revisions(id,user_id,parent_id,snapshot) VALUES (?,?,?,?::jsonb)",
                    revision.id(), owner, revision.parentId(), json.writeValueAsString(revision));
            return revision;
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
}
