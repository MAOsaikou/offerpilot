package com.offerpilot.career.application;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class DefaultCareerRecommendForChat implements CareerRecommendForChat {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CareerCardDetails details;
    private final CareerProfileService profiles;
    private final OccupationRecommender occupations;
    private final CareerAssessmentService assessments;
    public DefaultCareerRecommendForChat(CareerProfileService profiles, OccupationRecommender occupations) {
        this(profiles, occupations, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultCareerRecommendForChat(CareerProfileService profiles, OccupationRecommender occupations,
                                         CareerAssessmentService assessments) {
        this.profiles = profiles;
        this.occupations = occupations;
        this.assessments = assessments;
    }
    @Override public Result recommend(String owner) { return recommend(owner, ""); }
    @Override public Result recommend(String owner, String request) {
        var profile = profiles.get(owner);
        var confirmed = assessments == null ? null : assessments.confirmed(owner);
        if (confirmed != null) return fromAssessment(owner, profile, confirmed, request);
        var derived = InterestHollandMapping.derive(profile.interests());
        if (derived.code().isEmpty()) return new Result(
                "请先在探索页完成对话测评或勾选兴趣，或在对话里说明你对技术研发、产品策划、数据分析、创意设计、沟通协作或研究探索的偏好；保存兴趣后可获得职业推荐。", null);
        var cardEvidence=CareerCardDetails.evidence(profile,assessments==null?null:assessments.get(owner));
        if (details!=null && cardEvidence.size()<2) return new Result("推荐需要至少两条用户信息，请在画像中补充城市、专业、技能或另一项兴趣。",null);
        String query = String.join(" ", derived.interestsUsed()) + " " + profile.skills() + " " + profile.target() + " " + (request == null ? "" : request);
        var ranked = occupations.rankHybrid(derived.profile(), query.strip(), 8).stream().map(item ->
                new Recommendation(item.id(), item.name(), item.family(), item.hollandCode(), item.similarity(),
                        List.of(String.format(Locale.ROOT, "六维 RIASEC 兴趣画像相似度 r=%.3f（Pearson）；卡片值为 (r+1)/2", item.similarity() * 2 - 1),
                                "兴趣码 " + derived.code() + "，职业码 " + item.hollandCode() + "；字母码仅用于展示",
                                item.retrievalReason().isEmpty() ? "本项按兴趣画像排序；本轮未检索到对应职业文本" : item.retrievalReason()),
                        item.citations(), details==null?null:details.load(item.id(), cardEvidence))).toList();
        if (details!=null && ranked.size()<5) return new Result("职业库可用方向不足5个，请稍后重试；未填充虚构职业。",null);
        return new Result(null, new Payload(derived.code(), derived.interestsUsed(), ranked),
                new CurrentProfile(profile.target(), profile.city()));
    }
    /** CD-02 confirmed profile: six user-confirmed strengths, and reasons quoting the user's own words. */
    private Result fromAssessment(String owner, CareerProfileService.Profile profile,
                                  CareerAssessmentService.Confirmed confirmed, String request) {
        if (confirmed.code().isBlank()) return new Result("问卷六维兴趣相同，暂未分化；请补充对话测评或调整问卷后再推荐。",null);
        var assessment=assessments.get(owner);
        var answers = assessment.answers();
        var cardEvidence=CareerCardDetails.evidence(profile,assessment);
        // Hobbies, achievement and preferred work (steps 1-3) describe the person; feed them to retrieval.
        String story = String.join(" ", answers.subList(1, Math.min(4, answers.size())));
        String query = (profile.skills() + " " + profile.target() + " " + story + " " + (request == null ? "" : request)).strip();
        var quotes = confirmed.traits().stream().filter(t -> !t.evidence().isBlank()).limit(2)
                .map(t -> t.name() + "：「" + t.evidence() + "」").toList();
        String evidence = quotes.isEmpty() ? "画像依据：你在测评中确认的兴趣分数与特质" : "画像依据（你的原话）：" + String.join("；", quotes);
        var ranked = occupations.rankHybrid(CareerAssessmentService.profile(confirmed), query, 8).stream().map(item ->
                new Recommendation(item.id(), item.name(), item.family(), item.hollandCode(), item.similarity(),
                        List.of(String.format(Locale.ROOT, "与你确认的六维兴趣画像相似度 r=%.3f（Pearson）；卡片值为 (r+1)/2", item.similarity() * 2 - 1),
                                "你的兴趣码 " + confirmed.code() + "，职业码 " + item.hollandCode(), evidence,
                                item.retrievalReason().isEmpty() ? "本项按兴趣画像排序；本轮未检索到对应职业文本" : item.retrievalReason()),
                        item.citations(), details==null?null:details.load(item.id(), cardEvidence))).toList();
        if (details!=null && ranked.size()<5) return new Result("职业库可用方向不足5个，请稍后重试；未填充虚构职业。",null);
        return new Result(null, new Payload(confirmed.code(), List.of("对话测评"), ranked),
                new CurrentProfile(profile.target(), profile.city()));
    }

    @Override public OccupationRecommender.Ranked occupation(String occupationId, String name) {
        return occupations.find(occupationId, name);
    }
    @Override public CareerProfileService.Profile adopt(String owner, String occupationId, String name) {
        var occupation = occupation(occupationId, name);
        return profiles.saveTarget(owner, occupation.name());
    }
}
