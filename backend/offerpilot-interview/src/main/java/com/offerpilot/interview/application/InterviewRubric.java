package com.offerpilot.interview.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Observable text evidence, not an assessment of actual ability or hiring probability. Scores are 0–100. */
public final class InterviewRubric {
    public record Star(boolean situation, boolean task, boolean action, boolean result) {
        public int count() { return (situation ? 1 : 0) + (task ? 1 : 0) + (action ? 1 : 0) + (result ? 1 : 0); }
        public List<String> missing() {
            var parts = new ArrayList<String>();
            if (!situation) parts.add("S 背景：当时的场景与约束是什么？");
            if (!task) parts.add("T 任务：你的职责与目标是什么？");
            if (!action) parts.add("A 行动：你具体采取了哪些步骤，为什么？");
            if (!result) parts.add("R 结果：产生了什么可核对的变化或收获？没有数字也可以说明验证方式。");
            return List.copyOf(parts);
        }
    }
    public record Dimensions(Integer professional, Integer expression, Integer logic, Integer jobFit) {}
    public record Turn(String question, String answerSummary, Integer score, Star star, String feedback,
                       List<String> betterAnswerOutline, Dimensions dimensions, String improvedAnswer, String source, com.fasterxml.jackson.databind.JsonNode evaluation) {
        public Turn(String question,String answerSummary,Integer score,Star star,String feedback,List<String> outline) {
            this(question,answerSummary,score,star,feedback,outline,null,null,"rule",null);
        }
    }
    public record Learning(String topic, String reason, int estimatedHours) {}
    public record Presence(double eyeContactRatio, double smileRate, double headStability,
                           Double postureStability, String note) {}
    public record Report(String id, Integer overall, Dimensions dimensions, List<Turn> turns, List<String> strengths,
                         List<String> weaknesses, List<String> advice, List<Learning> recommendedLearning,
                         String note,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         Presence presence) {
        public Report(String id, Integer overall, Dimensions dimensions, List<Turn> turns, List<String> strengths,
                      List<String> weaknesses, List<String> advice, List<Learning> recommendedLearning, String note) {
            this(id, overall, dimensions, turns, strengths, weaknesses, advice, recommendedLearning, note, null);
        }
        public Report withPresence(Presence value) {
            return new Report(id, overall, dimensions, turns, strengths, weaknesses, advice, recommendedLearning,
                    note == null ? null : note.replace("端侧表现分析未启用。", value == null ? "端侧表现分析未启用或已跳过。" : ""), value);
        }
    }
    /** Deliberate compact tool narration; never concatenate a Java record into a prompt. */
    public static String summary(Report report) {
        var d = report.dimensions();
        if (report.overall()==null) return "尚未评分。"+report.note();
        return "综合线索分 " + report.overall() + "/100；专业 " + d.professional()
                + "，表达 " + d.expression() + "，逻辑 " + d.logic() + "，岗位关联 " + d.jobFit()
                + "。" + report.note();
    }
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:%|％|ms|秒|分钟|小时|天|次|个|人|倍|percent)", Pattern.CASE_INSENSITIVE);
    private InterviewRubric() {}
    public static Star detect(String answer) {
        String text = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return new Star(has(text, "当时", "背景", "场景", "在项目", "项目中", "期间", "situation", "during", "context"),
                has(text, "负责", "职责", "目标", "任务", "需要", "task", "goal", "responsible"),
                has(text, "我通过", "我采用", "我设计", "我实现", "我分析", "我拆", "我协调", "我测试", "我排查", "我先", "我们通过", "action", "implemented", "designed", "tested"),
                has(text, "结果", "最终", "提升", "降低", "减少", "完成", "学会", "收获", "验证", "result", "achieved", "improved", "reduced"));
    }
    private static boolean has(String text, String... markers) {
        for (String marker : markers) if (text.contains(marker)) return true;
        return false;
    }
    public static Report score(InterviewService.Session session) {
        var turns = new ArrayList<Turn>();
        int professional = 0, expression = 0, logic = 0, fit = 0;
        int[] coverage = new int[4];
        for (var answer : session.answers()) {
            String text = answer.fullText();
            Star star = detect(text);
            int length = text.codePointCount(0, text.length());
            int p = text.isEmpty() ? 0 : (star.task() ? 30 : 0) + (star.action() ? 45 : 0) + (star.result() ? 15 : 0) + (NUMBER.matcher(text).find() ? 10 : 0);
            int e = text.isEmpty() ? 0 : 20 + Math.min(40, length / 2) + (has(text, "。", ".", "；", ";", "首先", "然后") ? 20 : 0) + (star.count() >= 3 ? 20 : 0);
            int l = star.count() * 25;
            int j = text.isEmpty() ? 0 : (star.task() ? 30 : 0) + (star.action() ? 30 : 0) + (star.result() ? 20 : 0) + (text.toLowerCase(Locale.ROOT).contains(session.role().toLowerCase(Locale.ROOT)) ? 20 : 0);
            professional += p; expression += e; logic += l; fit += j;
            if (star.situation()) coverage[0]++;
            if (star.task()) coverage[1]++;
            if (star.action()) coverage[2]++;
            if (star.result()) coverage[3]++;
            var missing = star.missing();
            String feedback = text.isEmpty() ? "尚未作答，本题按 0 分计入；可继续面试后重新评分。"
                    : missing.isEmpty() ? "已观察到四项 STAR 线索，请核对行动与结果是否有事实支撑。"
                    : "尚未观察到：" + String.join("；", missing);
            var outline = List.of("S：说明真实场景和限制", "T：明确本人职责与目标", "A：按顺序说明具体行动及选择理由", "R：给出真实结果和验证方式，不补造数字", "岗位关联：说明这段经历如何支持「" + session.role() + "」");
            String summary = length > 180 ? text.substring(0, text.offsetByCodePoints(0, 180)) + "…" : text;
            turns.add(new Turn(answer.questionText(), summary, (p + e + l + j) / 4, star, feedback, outline));
        }
        int count = Math.max(1, turns.size());
        var dimensions = new Dimensions(professional / count, expression / count, logic / count, fit / count);
        var strengths = new ArrayList<String>();
        var weaknesses = new ArrayList<String>();
        var advice = new ArrayList<String>();
        var learning = new ArrayList<Learning>();
        String[] names = {"S 背景", "T 任务", "A 行动", "R 结果"};
        for (int i = 0; i < names.length; i++) {
            if (coverage[i] == turns.size() && !turns.isEmpty()) strengths.add(names[i] + "：每题均观察到相应文本线索");
            else {
                String reason = names[i] + "：" + (turns.size() - coverage[i]) + " 题未观察到线索";
                weaknesses.add(reason);
                advice.add("优先补充 " + names[i] + "，按每题的改进提纲重答；只使用真实经历。");
                learning.add(new Learning(names[i] + "叙述练习", reason, 1));
            }
        }
        if (dimensions.jobFit() < 60) {
            weaknesses.add("岗位匹配：职责、行动、结果与岗位名称的文本关联较少，不代表不具备岗位能力");
            advice.add("补充经历与目标岗位的关联，说明可迁移的能力及证据。");
            learning.add(new Learning("岗位关联表达", "岗位匹配文本分低于 60", 1));
        }
        if (dimensions.expression() < 60) advice.add("用短句交代背景、任务、行动和结果，补充具体步骤。");
        if (strengths.isEmpty()) strengths.add("目前证据不足，请补充答案后重新评分。");
        if (advice.isEmpty()) advice.add("核对每项结果的事实依据，并练习精简表达。");
        int overall = (dimensions.professional() + dimensions.expression() + dimensions.logic() + dimensions.jobFit()) / 4;
        return new Report(session.id(), overall, dimensions, List.copyOf(turns), List.copyOf(strengths),
                List.copyOf(weaknesses), List.copyOf(advice), List.copyOf(learning),
                (session.completed() ? "已完成会话" : "当前答案快照（未标记完成）") + "；文字规则评分 0–100，仅衡量表达线索，不验证事实或预测录用。端侧表现分析未启用。");
    }
}
