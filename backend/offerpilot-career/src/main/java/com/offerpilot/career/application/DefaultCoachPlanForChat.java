package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** Evidence-based heuristics, independent of models and embeddings. Absence is not proof of inability. */
@Service
public class DefaultCoachPlanForChat implements CoachPlanForChat {
    private final CareerProfileService profiles;
    private final OccupationRecommender occupations;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectProvider<CoachResumeSkills> resumes;

    public DefaultCoachPlanForChat(CareerProfileService profiles, OccupationRecommender occupations,
                                  JdbcTemplate jdbc, ObjectMapper json, ObjectProvider<CoachResumeSkills> resumes) {
        this.profiles = profiles; this.occupations = occupations; this.jdbc = jdbc; this.json = json; this.resumes = resumes;
    }

    @Override public Result plan(String owner, String resumeId, String versionId, String requestId) {
        var profile = profiles.get(owner);
        if (profile.target() == null || profile.target().isBlank()) return new Result(
                "请先在探索页选择职业并点击「设为目标」，批准后再来分析技能差距与制定学习计划。", null, null);
        OccupationRecommender.Ranked occupation;
        try { occupation = occupations.find(null, profile.target().strip()); }
        catch (BusinessException ex) {
            if (ex.errorCode() != ErrorCode.VALIDATION_FAILED) throw ex;
            return new Result("当前目标尚未关联职业库，请在探索页选择推荐职业并「设为目标」后重试。", null, null);
        }
        var stored = jdbc.queryForObject("SELECT core_skills::text FROM occupations WHERE id=?", String.class, occupation.id());
        JsonNode required;
        try { required = json.readTree(stored); }
        catch (Exception ex) { throw new IllegalStateException("Invalid stored occupation skills", ex); }
        var reader = resumes.getIfAvailable();
        var resume = reader == null ? new CoachResumeSkills.Snapshot(false, List.of()) : reader.read(owner, resumeId, versionId);
        var skills = gaps(required, profile.skills(), resume.skills());
        String summary = "面向「" + occupation.name() + "」的技能证据差距与四周学习建议。"
                + (resume.available() ? "已参考保存的结构化简历。" : "未读取到可用简历，仅参考档案技能；可上传或选择已保存简历补充证据。")
                + "等级为规则参考：档案提及=2、简历列出=3；无证据=0，不代表没有能力。职业库技能可能为通用技能，不代表完整岗位要求。"
                + (skills.isEmpty() ? "当前职业未录入核心技能，暂不能判断具体差距；先梳理目标岗位要求。" : "优先补齐证据差距最大的技能。")
                + "卡片未写入成长任务；仅勾选已完成的任务并批准同步后记录完成状态。";
        var themes = skills.stream().filter(s -> s.gap() > 0).limit(2).map(Skill::name).toList();
        var weeks = List.of(
                new Week(1, themes.isEmpty() ? "梳理目标岗位要求" : "补齐 " + themes.get(0),
                        List.of(item(requestId, 0, 2, profile), item(requestId, 2, 4, profile))),
                new Week(2, themes.size() < 2 ? "项目证据" : "补齐 " + themes.get(1) + " · 项目证据",
                        List.of(item(requestId, 1, 3, profile))),
                new Week(3, "整理项目成果与面试表达", List.of(item(requestId, 3, 3, profile), item(requestId, 4, 2, profile))),
                new Week(4, "复盘与调整", List.of(item(requestId, 5, 2, profile))));
        return new Result(summary,
                new SkillGapPayload(requestId, new Target("OCCUPATION", occupation.id(), occupation.name()), skills, summary, List.of()),
                new RoadmapPayload(weeks, List.of(new Milestone("完成核心技能复盘与项目证据整理", "第2周末"),
                        new Milestone("完成文字面试并调整下一轮计划", "第4周末"))));
    }

    static List<Skill> gaps(JsonNode required, String profile, List<String> resume) {
        Set<String> profileNames = new HashSet<>();
        if (profile != null) Arrays.stream(profile.split("[,，、;；\\s]+"))
                .filter(s -> !s.isBlank()).map(DefaultCoachPlanForChat::normalize).forEach(profileNames::add);
        Set<String> resumeNames = new HashSet<>();
        resume.stream().map(DefaultCoachPlanForChat::normalize).forEach(resumeNames::add);
        Map<String, Skill> unique = new LinkedHashMap<>();
        if (required != null && required.isArray()) for (var node : required) {
            String name = node.isTextual() ? node.asText().strip() : node.path("name").asText().strip();
            if (name.isEmpty()) continue;
            int level = node.path("required").isNumber() ? node.path("required").asInt()
                    : node.path("level").isNumber() ? node.path("level").asInt() : 4;
            level = Math.max(1, Math.min(5, level));
            String key = normalize(name);
            var evidence = new ArrayList<String>();
            int current = 0;
            if (profileNames.contains(key)) { current = 2; evidence.add("职业档案技能提及：" + name); }
            if (resumeNames.contains(key)) { current = 3; evidence.add("已保存简历技能或项目技术列出：" + name); }
            var skill = new Skill(name, level, current, Math.max(0, level-current), List.copyOf(evidence), false);
            // Keep the strongest requirement when the source repeats a skill.
            unique.merge(key, skill, (a,b) -> a.required() >= b.required() ? a : b);
        }
        var sorted = unique.values().stream().sorted(Comparator.comparingInt(Skill::gap).reversed().thenComparing(Skill::name)).toList();
        var result = new ArrayList<Skill>();
        for (int i=0; i<sorted.size(); i++) {
            var s = sorted.get(i);
            result.add(new Skill(s.name(), s.required(), s.current(), s.gap(), s.evidence(), i<2 && s.gap()>0));
        }
        return List.copyOf(result);
    }
    private static String normalize(String name) { return name.strip().toLowerCase(Locale.ROOT); }
    private static Item item(String id, int index, int hours, CareerProfileService.Profile profile) {
        String title = GrowthTaskCatalog.TITLES.get(index);
        return new Item(id + "-task-" + index, title, hours,
                List.of(switch (index) {
                    case 0 -> "目标岗位职责与职业库核心技能清单";
                    case 1 -> "已有项目材料与 STAR 复盘模板";
                    case 2 -> "对应技能的官方入门文档与练习笔记";
                    case 3 -> "已保存简历中的项目成果";
                    case 4 -> "OfferPilot 文字模拟面试";
                    default -> "本周投递记录与学习笔记";
                }), null, profile.completedTasks().contains(title));
    }
    @Override public CareerProfileService.Profile adoptGrowthPlan(String owner, List<String> taskTitles) {
        return profiles.mergeTasks(owner, taskTitles);
    }
}
