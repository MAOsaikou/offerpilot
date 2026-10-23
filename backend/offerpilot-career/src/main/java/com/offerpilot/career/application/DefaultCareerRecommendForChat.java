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
        var assessment = assessments == null ? null : assessments.get(owner);
        var confirmed = assessments == null ? profile.assessment() : assessments.confirmed(owner);
        var derived = InterestHollandMapping.derive(profile.interests());
        if (confirmed != null && confirmed.code().isBlank()) return new Result(
                "问卷六维兴趣相同，暂未分化；请补充对话测评或调整问卷后再推荐。", null);
        if (confirmed == null && derived.code().isEmpty()) return new Result(
                "请先在探索页完成对话测评或勾选兴趣，或在对话里说明你对技术研发、产品策划、数据分析、创意设计、沟通协作或研究探索的偏好；保存兴趣后可获得职业推荐。", null);
        var evidence = CareerCardDetails.evidence(profile, assessment);
        if (evidence.size() < 2) return new Result("推荐需要至少两条用户信息，请在画像中补充城市、专业、技能或另一项兴趣。", null);
        String code = confirmed == null ? derived.code() : confirmed.code();
        RiasecProfile interest = confirmed == null ? derived.profile() : CareerAssessmentService.profile(confirmed);
        String quotes = "画像依据（你的原话/保存字段）：" + String.join("；", evidence.stream()
                .map(e -> e.field() + "：「" + e.value() + "」（" + e.source() + "）").toList());
        var ranked = occupations.rankForUser(interest, profile, assessment, request, 8).stream().map(item ->
                new Recommendation(item.id(), item.name(), item.family(), item.hollandCode(), item.similarity(),
                        List.of(String.format(Locale.ROOT, "六维 RIASEC 兴趣画像相似度 r=%.3f（Pearson）；兴趣值为 (r+1)/2；你的兴趣码 %s，职业码 %s", item.similarity() * 2 - 1, code, item.hollandCode()),
                                quotes, item.retrievalReason()), item.citations(),
                        details == null ? null : details.load(item.id(), evidence), item.rankingScore())).toList();
        if (ranked.size() < 5) return new Result("职业库达到最低匹配要求的方向不足5个（当前" + ranked.size()
                + "个，最低综合分0.60），请补充目标、专业或技能后重试；未用低匹配或虚构职业补位。", null);
        return new Result(null, new Payload(code, confirmed == null ? derived.interestsUsed() : List.of("已确认测评"),
                ranked, "constraints"), new CurrentProfile(profile.target(), profile.city()));
    }

    @Override public OccupationRecommender.Ranked occupation(String occupationId, String name) {
        return occupations.find(occupationId, name);
    }
    @Override public CareerProfileService.Profile adopt(String owner, String occupationId, String name) {
        var occupation = occupation(occupationId, name);
        return profiles.saveTarget(owner, occupation.name());
    }
}
