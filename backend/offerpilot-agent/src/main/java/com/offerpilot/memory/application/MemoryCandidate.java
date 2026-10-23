package com.offerpilot.memory.application;

import java.util.regex.Pattern;
import static com.offerpilot.memory.application.UserMemoryService.*;

/** Conservative, deterministic extraction from ONE current USER message only; never accepts model output. */
public final class MemoryCandidate {
    public record Candidate(Kind kind, String fact, Source source) {}
    private static final Pattern EXPLICIT = Pattern.compile("^(?:请)?(?:帮我)?(?:记住|记得)(?:一下)?[：:,，\\s]*(.+)$");
    private static final Pattern FUTURE = Pattern.compile("^(?:请)?以后都按(.+)$");
    private static final Pattern DOCUMENT = Pattern.compile("(?i)(简历|JD|笔记|引用|原文|以下|附件|文档|系统提示|工具输出|assistant|system|```|[<>“”\"「」])");
    private static final Pattern NON_ASSERTION = Pattern.compile("如果|假如|假设|也许|可能|不确定|没决定|还没想好|他说|她说|朋友说|[？?]");
    // Omitted subjects are accepted only for narrow company preferences and city constraints.
    private static final Pattern COMPANY_PREFERENCE = Pattern.compile(
            "^(?:比较)?偏好(?:大型|中型|小型)?(?:互联网|科技|国有|民营|外资|初创|创业)?(?:公司|企业)[。.]?$");
    private static final Pattern CITY_CONSTRAINT = Pattern.compile(
            "^(?:我)?(?:比较)?(?:不考虑|只考虑)(?:北京|上海|广州|深圳|杭州|成都|武汉|南京|苏州|西安|重庆|天津|长沙|郑州|合肥|厦门|福州|济南|青岛|大连|沈阳|宁波|无锡|东莞|佛山|珠海)(?:市)?[。.]?$");
    private MemoryCandidate() {}
    public static boolean explicit(String content) {
        String text = content.strip();
        return EXPLICIT.matcher(text).matches() || FUTURE.matcher(text).matches();
    }
    public static Candidate extract(String content) {
        if (content == null || content.isBlank() || DOCUMENT.matcher(content).find()) return null;
        String text = content.strip();
        var explicit = EXPLICIT.matcher(text);
        String fact;
        Source source;
        if (explicit.matches()) { fact = explicit.group(1).strip(); source = Source.EXPLICIT; }
        else if (FUTURE.matcher(text).matches()) { fact = text; source = Source.EXPLICIT; }
        else {
            if (NON_ASSERTION.matcher(text).find()) return null;
            // First-person assertions or narrowly defined subject-omitted clauses only.
            fact = null;
            for (String sentence : text.split("[。！!；;\\n]")) {
                String value = sentence.strip();
                if (value.matches("^(?:我的(?:目标(?:城市|岗位|职业)|期望(?:城市|岗位)|技术栈|技能|偏好|约束)(?:是|为)|我(?:(?:以后)?(?:想在|希望在)|想做|希望做|擅长|熟悉|只考虑|不接受|偏好)).+")
                        || COMPANY_PREFERENCE.matcher(value).matches() || CITY_CONSTRAINT.matcher(value).matches()) {
                    fact = value; break;
                }
            }
            source = Source.SUGGESTED_ACCEPTED;
        }
        if (fact == null || fact.contains("？") || fact.contains("?") || fact.contains("\n") || fact.contains("\r")) return null;
        Kind kind = source == Source.SUGGESTED_ACCEPTED && CITY_CONSTRAINT.matcher(fact).matches()
                ? Kind.CONSTRAINT : classify(fact);
        try { validate(new Draft(kind, fact)); }
        catch (com.offerpilot.common.error.BusinessException ex) { return null; }
        // Text is always copied verbatim from content; no inference or generated paraphrase.
        if (!content.contains(fact)) return null;
        return new Candidate(kind, fact, source);
    }
    private static Kind classify(String fact) {
        if (fact.matches(".*(?:不接受|只考虑|约束|不能|不想加班).*")) return Kind.CONSTRAINT;
        if (fact.matches(".*(?:城市|想在|希望在).*")) return Kind.LOCATION;
        if (fact.matches(".*(?:目标|期望岗位|想做|希望做).*")) return Kind.GOAL;
        if (fact.matches(".*(?:技术栈|技能|擅长|熟悉).*")) return Kind.SKILL;
        if (fact.matches(".*(?:偏好|以后都按).*")) return Kind.PREFERENCE;
        return Kind.OTHER;
    }
}
