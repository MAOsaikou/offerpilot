package com.offerpilot.career.application;

import java.util.List;
import java.util.Map;

/** Fixed DiscoverView chips, equal boosts; ties use HollandMatcher's RIASEC order. */
public final class InterestHollandMapping {
    private static final String LETTERS = "RIASEC";
    private static final Map<String, String> CHIPS = Map.of(
            "技术研发", "IR", "产品策划", "ESA", "数据分析", "IC",
            "创意设计", "A", "沟通协作", "SE", "研究探索", "IR");
    private InterestHollandMapping() {}
    public record Derived(String code, List<String> interestsUsed, RiasecProfile profile) {}
    public static Derived derive(List<String> interests) {
        var used = interests.stream().filter(CHIPS::containsKey).distinct().sorted().toList();
        if (used.isEmpty()) return new Derived("", used, new RiasecProfile(0, 0, 0, 0, 0, 0));
        double[] scores = new double[6];
        used.forEach(chip -> CHIPS.get(chip).chars().forEach(c -> scores[LETTERS.indexOf(c)]++));
        String top = HollandMatcher.topThree(scores[0], scores[1], scores[2], scores[3], scores[4], scores[5]);
        // Never pad with a zero-score dimension (e.g. design alone yields A, not ARI).
        String code = top.chars().filter(c -> scores[LETTERS.indexOf(c)] > 0)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return new Derived(code, used, new RiasecProfile(scores[0], scores[1], scores[2], scores[3], scores[4], scores[5]));
    }
}
