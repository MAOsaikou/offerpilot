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
        var ranked = occupations.rankHybrid(derived.profile(), query.strip(), 8).stream().map(item ->
                new Recommendation(item.id(), item.name(), item.family(), item.hollandCode(), item.similarity(),
                        List.of(String.format(Locale.ROOT, "六维 RIASEC 兴趣画像相似度 r=%.3f（Pearson）；卡片值为 (r+1)/2", item.similarity() * 2 - 1),
                                "兴趣码 " + derived.code() + "，职业码 " + item.hollandCode() + "；字母码仅用于展示",
                                item.retrievalReason().isEmpty() ? "本项按兴趣画像排序；本轮未检索到对应职业文本" : item.retrievalReason()),
                        item.citations())).toList();
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
