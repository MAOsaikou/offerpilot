package com.offerpilot.career.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CD-01: a fixed conversational state machine — one question per turn, seven turns — then CD-02
 * inference and a user-confirmed profile that recommendations use instead of the interest chips.
 */
@Service
public class CareerAssessmentService {
    public static final List<String> QUESTIONS = List.of(
            "先认识一下：你学什么专业、现在几年级？",
            "课余时间你最常做、做起来会忘了时间的是什么事？",
            "说一件让你特别有成就感的经历：当时你具体做了什么？",
            "你更享受哪类事情：动手做出东西、钻研弄懂原理、创作表达、帮助别人、组织推动项目，还是把事情整理得井井有条？可以举个例子。",
            "在团队里你通常扮演什么角色？同学或朋友会怎么形容你？",
            "选工作时你最看重什么？比如成长、收入、稳定、兴趣、影响力、工作生活平衡。",
            "最后说说现实条件：意向城市、期望月薪，能接受多少加班？没想好也可以直说。");
    public static final int MIN_ANSWERS = 4;

    public record Confirmed(Map<String, Integer> riasec, String code, List<CareerAssessmentInference.Trait> traits,
                            CareerAssessmentInference.Constraints constraints) {}
    public record Assessment(List<String> questions, List<String> answers, CareerAssessmentInference.Inference inference,
                             Confirmed confirmed, String confirmedAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CareerAssessmentInference inference;
    private final CareerProfileService profiles;

    public CareerAssessmentService(JdbcTemplate jdbc, ObjectMapper json, CareerAssessmentInference inference,
                                   CareerProfileService profiles) {
        this.jdbc = jdbc;
        this.json = json;
        this.inference = inference;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public Assessment get(String owner) {
        var rows = jdbc.query("SELECT * FROM career_assessments WHERE user_id=?", (rs, row) -> new Assessment(QUESTIONS,
                answers(rs.getString("answers")), read(rs.getString("inference"), CareerAssessmentInference.Inference.class),
                read(rs.getString("confirmed"), Confirmed.class),
                rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant().toString()), owner);
        return rows.isEmpty() ? new Assessment(QUESTIONS, answers("[]"), null, null, null) : rows.get(0);
    }

    /** Confirmed profile for recommendations, or null when the user has not confirmed one. */
    @Transactional(readOnly = true)
    public Confirmed confirmed(String owner) { return get(owner).confirmed(); }

    @Transactional
    public Assessment saveAnswer(String owner, int step, String answer) {
        if (step < 0 || step >= QUESTIONS.size()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "题目序号必须为 0-6。");
        if (answer == null || answer.codePointCount(0, answer.length()) > 1000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "回答最多 1000 字。");
        lockRow(owner);
        var answers = new ArrayList<>(get(owner).answers());
        String text = answer.strip();
        if (text.equals(answers.get(step))) return get(owner);
        answers.set(step, text);
        // An inference about other words is stale; the confirmed profile stays until the user reconfirms.
        jdbc.update("UPDATE career_assessments SET answers=?::jsonb, inference=NULL WHERE user_id=?", write(answers), owner);
        return get(owner);
    }

    /** Provider call holds no transaction; the result lands only if the answers did not change meanwhile. */
    public Assessment infer(String owner) {
        var current = get(owner);
        var qa = new ArrayList<CareerAssessmentInference.QA>();
        for (int i = 0; i < QUESTIONS.size(); i++)
            if (!current.answers().get(i).isBlank()) qa.add(new CareerAssessmentInference.QA(QUESTIONS.get(i), current.answers().get(i)));
        if (qa.size() < MIN_ANSWERS)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请至少回答 " + MIN_ANSWERS + " 个问题，再生成画像。");
        var result = inference.infer(owner, qa);
        jdbc.update("UPDATE career_assessments SET inference=?::jsonb WHERE user_id=? AND answers=?::jsonb",
                write(result), owner, write(current.answers()));
        return get(owner);
    }

    @Transactional
    public Assessment confirm(String owner, Map<String, Integer> riasec, List<String> traitNames,
                              CareerAssessmentInference.Constraints constraints) {
        var scores = new LinkedHashMap<String, Integer>();
        for (char letter : CareerAssessmentInference.LETTERS.toCharArray()) {
            Integer value = riasec == null ? null : riasec.get(String.valueOf(letter));
            if (value == null || value < 0 || value > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "六个兴趣维度都需要 0-100 的分数。");
            scores.put(String.valueOf(letter), value);
        }
        if (scores.values().stream().distinct().count() == 1)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "六个维度分数完全相同，无法区分兴趣倾向，请调整后再确认。");
        lockRow(owner);
        var inferred = get(owner).inference();
        var byName = new LinkedHashMap<String, CareerAssessmentInference.Trait>();
        if (inferred != null) inferred.traits().forEach(t -> byName.put(t.name(), t));
        // Kept traits keep their evidence; user-added ones are marked as self-reported (no quote).
        var traits = traitNames.stream().map(String::strip).filter(n -> !n.isEmpty()).distinct()
                .map(n -> byName.getOrDefault(n, new CareerAssessmentInference.Trait(n, 1.0, ""))).toList();
        var confirmed = new Confirmed(scores, code(scores), traits, constraints);
        jdbc.update("UPDATE career_assessments SET confirmed=?::jsonb, confirmed_at=now() WHERE user_id=?", write(confirmed), owner);
        if (!constraints.city().isBlank()) {
            var profile = profiles.get(owner);
            profiles.save(owner, profile.target(), constraints.city(), profile.skills(), profile.interests());
        }
        return get(owner);
    }

    @Transactional
    public void reset(String owner) {
        jdbc.update("DELETE FROM career_assessments WHERE user_id=?", owner);
    }

    public static String code(Map<String, Integer> scores) {
        return HollandMatcher.topThree(scores.get("R"), scores.get("I"), scores.get("A"),
                scores.get("S"), scores.get("E"), scores.get("C"));
    }

    public static RiasecProfile profile(Confirmed confirmed) {
        var s = confirmed.riasec();
        return new RiasecProfile(s.get("R"), s.get("I"), s.get("A"), s.get("S"), s.get("E"), s.get("C"));
    }

    private void lockRow(String owner) {
        jdbc.update("INSERT INTO career_assessments(user_id,answers) VALUES (?,?::jsonb) ON CONFLICT(user_id) DO NOTHING",
                owner, write(Collections.nCopies(QUESTIONS.size(), "")));
        jdbc.queryForObject("SELECT user_id FROM career_assessments WHERE user_id=? FOR UPDATE", String.class, owner);
    }

    private List<String> answers(String raw) {
        List<String> stored = read(raw, new TypeReference<List<String>>() {});
        var out = new ArrayList<>(Collections.nCopies(QUESTIONS.size(), ""));
        if (stored != null) for (int i = 0; i < Math.min(stored.size(), out.size()); i++) out.set(i, stored.get(i) == null ? "" : stored.get(i));
        return List.copyOf(out);
    }

    private <T> T read(String raw, Class<T> type) {
        try { return raw == null ? null : json.readValue(raw, type); }
        catch (Exception ex) { return null; }
    }

    private <T> T read(String raw, TypeReference<T> type) {
        try { return raw == null ? null : json.readValue(raw, type); }
        catch (Exception ex) { return null; }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
