package com.offerpilot.career.application;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class DefaultCareerRecommendForChat implements CareerRecommendForChat {
    private final CareerProfileService profiles;
    private final OccupationRecommender occupations;
    public DefaultCareerRecommendForChat(CareerProfileService profiles, OccupationRecommender occupations) {
        this.profiles = profiles;
        this.occupations = occupations;
    }
    @Override public Result recommend(String owner) { return recommend(owner, ""); }
    @Override public Result recommend(String owner, String request) {
        var profile = profiles.get(owner);
        var derived = InterestHollandMapping.derive(profile.interests());
        if (derived.code().isEmpty()) return new Result(
                "请先在探索页勾选兴趣，或在对话里说明你对技术研发、产品策划、数据分析、创意设计、沟通协作或研究探索的偏好；保存兴趣后可获得职业推荐。", null);
        String query = String.join(" ", derived.interestsUsed()) + " " + profile.skills() + " " + profile.target() + " " + (request == null ? "" : request);
        var ranked = occupations.rankHybrid(derived.code(), query.strip(), 8).stream().map(item -> {
            String overlap = derived.code().chars().filter(c -> item.hollandCode().indexOf(c) >= 0)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            // Existing rank scores are raw weighted points; expose a 0..1 fraction of attainable points.
            double maximum = switch (derived.code().length()) { case 1 -> 3; case 2 -> 5; default -> 6; };
            double similarity = item.similarity() / maximum;
            return new Recommendation(item.id(), item.name(), item.family(), item.hollandCode(), similarity,
                    List.of("兴趣码 " + derived.code() + " 与职业码 " + item.hollandCode() + " 共同包含 " + overlap,
                            String.format(Locale.ROOT, "按兴趣码位置加权的相似度为 %.0f%%；仅作探索参考，不代表能力或录用概率", similarity * 100), OccupationRelevance.reason(query, item)),
                    occupations.citations(item.id()));
        }).toList();
        return new Result(null, new Payload(derived.code(), derived.interestsUsed(), ranked));
    }
    @Override public OccupationRecommender.Ranked occupation(String occupationId, String name) {
        return occupations.find(occupationId, name);
    }
    @Override public CareerProfileService.Profile adopt(String owner, String occupationId, String name) {
        var occupation = occupation(occupationId, name);
        return profiles.saveTarget(owner, occupation.name());
    }
}
