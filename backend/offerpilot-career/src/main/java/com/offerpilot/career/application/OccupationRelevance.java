package com.offerpilot.career.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Explicit field/skill signals disambiguate Holland interests; no inferred major or vector evidence. */
final class OccupationRelevance {
    private static final Set<String> SOFTWARE_FAMILIES = Set.of("软件研发", "算法与人工智能", "测试与质量", "运维与安全", "数据", "计算机与数学");
    private static final List<String> SOFTWARE_SIGNALS = List.of("计算机", "软件", "编程", "后端", "前端", "全栈", "java", "python", "spring", "mysql", "redis", "开发系统", "搭建系统", "构建系统");
    private OccupationRelevance() {}
    static double score(String query, OccupationRecommender.Ranked occupation) {
        String context = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (!SOFTWARE_FAMILIES.contains(occupation.family())) return 0;
        boolean explicit = SOFTWARE_SIGNALS.stream().anyMatch(context::contains);
        // Generic R&D alone is weaker than a stated software target or concrete programming skill.
        return explicit ? 1 : context.contains("技术研发") ? 0.5 : 0;
    }
    static String reason(String query, OccupationRecommender.Ranked occupation) {
        double relevance = score(query, occupation);
        if (relevance == 0) return "排序保留 Holland 兴趣相似度，未命中明确的软件技术方向信号";
        return relevance == 1 ? "结合本轮文字、已保存目标或技能中的软件技术方向信号，优先探索「" + occupation.family() + "」；未推断专业或能力"
                : "兴趣标签「技术研发」作为弱方向信号，优先探索「" + occupation.family() + "」；可补充目标与技能细化排序";
    }
}
