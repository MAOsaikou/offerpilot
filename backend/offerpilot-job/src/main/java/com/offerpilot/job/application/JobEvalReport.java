package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic A–F evaluation card from validated match evidence + job text signals.
 * Model prose is not required; scores and reasons are code-derived.
 */
public final class JobEvalReport {
    public static final String CARD_TYPE = "job_eval_report";
    private static final Pattern SALARY = Pattern.compile(
            "(?:\\d{1,3}(?:\\.\\d)?\\s*[-~～到至]\\s*\\d{1,3}(?:\\.\\d)?\\s*(?:[kKwW万]|千))|(?:月薪|年薪|薪资|薪酬|base|package)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RED_FLAG = Pattern.compile(
            "不缴(?:纳)?社保|无底薪|纯提成|传销|加盟费|押金|培训贷|一句不谈技术|日结|加班不调休|无偿试用",
            Pattern.CASE_INSENSITIVE);

    private JobEvalReport() {}

    public static ObjectNode evaluate(JsonNode matchReport, JsonNode job) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("cardType", CARD_TYPE);
        out.put("matchId", text(matchReport, "id"));
        out.put("jobId", text(job, "id"));
        out.put("title", text(job, "title"));
        out.put("company", text(job, "company"));

        Counts counts = counts(matchReport.path("requirements"));
        String jobText = (text(job, "description") + "\n" + text(job, "requirement") + "\n" + text(job, "title")).toLowerCase(Locale.ROOT);
        boolean salarySignal = SALARY.matcher(jobText).find() || SALARY.matcher(text(job, "salary")).find();
        boolean redFlag = RED_FLAG.matcher(jobText).find();
        int infoScore = infoCompleteness(job);
        int coverage = coverageScore(counts);
        int evidence = evidenceScore(counts, matchReport.path("requirements"));
        int compensation = salarySignal ? 72 : ("social".equals(text(job, "nature")) ? 48 : 58);
        if (redFlag) compensation = Math.min(compensation, 35);
        int risk = riskScore(counts, redFlag);
        int priority = Math.round(0.35f * coverage + 0.25f * evidence + 0.15f * infoScore + 0.10f * compensation + 0.15f * (100 - risk));

        ArrayNode dims = out.putArray("dimensions");
        dims.add(dimension("A", "要求覆盖", coverage, coverageReason(counts)));
        dims.add(dimension("B", "证据强度", evidence, evidenceReason(counts)));
        dims.add(dimension("C", "岗位信息完整度", infoScore, infoReason(job, infoScore)));
        dims.add(dimension("D", "薪酬与级别信号", compensation, compensationReason(job, salarySignal, redFlag)));
        dims.add(dimension("E", "风险与红旗", 100 - risk, riskReason(counts, redFlag, risk)));
        dims.add(dimension("F", "投递优先级", priority, priorityReason(priority, coverage, risk)));

        int overall = Math.round((coverage + evidence + infoScore + compensation + (100 - risk) + priority) / 6f);
        out.put("overall", overall);
        out.put("grade", grade(overall));
        out.put("summary", summary(overall, counts, redFlag, salarySignal));
        ArrayNode advice = out.putArray("advice");
        for (String line : adviceLines(counts, redFlag, salarySignal, infoScore)) advice.add(line);
        out.put("scoringVersion", "job-eval-v1");
        out.put("note", "分数由匹配证据与岗位文本规则计算，不是录用预测；薪酬/红旗仅看公开文本信号。");
        return out;
    }

    private record Counts(int total, int met, int partial, int missing) {}

    private static Counts counts(JsonNode requirements) {
        int met = 0, partial = 0, missing = 0, total = 0;
        if (requirements.isArray()) {
            for (JsonNode item : requirements) {
                total++;
                switch (item.path("status").asText()) {
                    case "MET" -> met++;
                    case "PARTIAL" -> partial++;
                    default -> missing++;
                }
            }
        }
        return new Counts(total, met, partial, missing);
    }

    private static int coverageScore(Counts c) {
        if (c.total == 0) return 0;
        return Math.round(100f * (2 * c.met + c.partial) / (2f * c.total));
    }

    private static int evidenceScore(Counts c, JsonNode requirements) {
        if (c.total == 0) return 0;
        int quoted = 0;
        for (JsonNode item : requirements) {
            if ("MET".equals(item.path("status").asText()) && !item.path("resumeQuote").asText("").isBlank()) quoted++;
            if ("PARTIAL".equals(item.path("status").asText()) && !item.path("resumeQuote").asText("").isBlank()) quoted++;
        }
        int base = coverageScore(c);
        int quoteBoost = Math.round(20f * quoted / c.total);
        return Math.min(100, base / 2 + quoteBoost + (c.met * 40 / c.total));
    }

    private static int infoCompleteness(JsonNode job) {
        int score = 20;
        if (!text(job, "description").isBlank()) score += Math.min(30, text(job, "description").codePointCount(0, text(job, "description").length()) / 20);
        if (!text(job, "requirement").isBlank()) score += Math.min(30, text(job, "requirement").codePointCount(0, text(job, "requirement").length()) / 20);
        if (job.path("skills").isArray() && job.path("skills").size() > 0) score += 15;
        if (!text(job, "locations").isBlank()) score += 5;
        return Math.min(100, score);
    }

    private static int riskScore(Counts c, boolean redFlag) {
        int risk = redFlag ? 55 : 15;
        if (c.total > 0) risk += Math.round(40f * c.missing / c.total);
        return Math.min(100, risk);
    }

    private static ObjectNode dimension(String code, String name, int score, String reason) {
        return JsonNodeFactory.instance.objectNode()
                .put("code", code).put("name", name).put("score", clamp(score)).put("reason", reason);
    }

    private static String coverageReason(Counts c) {
        if (c.total == 0) return "尚无匹配条目。";
        return "共 " + c.total + " 项要求：明确 " + c.met + "、部分 " + c.partial + "、未体现 " + c.missing + "。";
    }

    private static String evidenceReason(Counts c) {
        if (c.total == 0) return "缺少可核对的匹配条目。";
        return "以简历原文引用支撑的要求越多，证据强度越高；当前明确命中 " + c.met + " 项。";
    }

    private static String infoReason(JsonNode job, int score) {
        return score >= 70 ? "职责与要求文本较完整，便于核对。"
                : "岗位描述或要求偏短，评估信息有限（来源：" + text(job, "source") + "）。";
    }

    private static String compensationReason(JsonNode job, boolean salary, boolean redFlag) {
        if (redFlag) return "文本出现高风险措辞，薪酬信号可信度下调。";
        if (salary) return "岗位文本中出现薪酬相关表述（未验证真实性）。";
        String nature = text(job, "nature");
        return "未读到明确薪酬区间；按岗位类型「" + (nature.isBlank() ? "未知" : nature) + "」给出中性分。";
    }

    private static String riskReason(Counts c, boolean redFlag, int risk) {
        List<String> parts = new ArrayList<>();
        if (redFlag) parts.add("命中红旗关键词");
        if (c.total > 0 && c.missing > 0) parts.add("有 " + c.missing + " 项要求在简历中未体现");
        if (parts.isEmpty()) return "未见明显文本红旗，缺口可控。";
        return String.join("；", parts) + "（风险分 " + risk + "，展示为反向安全分）。";
    }

    private static String priorityReason(int priority, int coverage, int risk) {
        if (priority >= 75) return "覆盖较好且风险可控，可优先准备投递材料。";
        if (coverage < 50 || risk >= 60) return "缺口或风险偏高，建议先补证据再投。";
        return "中等匹配，可结合目标城市与成长路径决定是否投递。";
    }

    private static String summary(int overall, Counts c, boolean redFlag, boolean salary) {
        return "综合 " + overall + "/100（" + grade(overall) + "）。要求覆盖 "
                + coverageScore(c) + "，"
                + (redFlag ? "存在文本红旗；" : "未见文本红旗；")
                + (salary ? "有薪酬信号。" : "无明确薪酬区间。");
    }

    private static List<String> adviceLines(Counts c, boolean redFlag, boolean salary, int info) {
        List<String> lines = new ArrayList<>();
        if (c.missing > 0) lines.add("优先用真实项目补齐 " + c.missing + " 项未体现要求，并保留可核对细节。");
        if (c.partial > 0) lines.add("把部分命中的经历写清职责边界与结果验证方式。");
        if (!salary) lines.add("投递前在官网或沟通中确认薪酬与级别，勿依赖推断。");
        if (redFlag) lines.add("出现红旗措辞时，先核实用工合规再投递。");
        if (info < 60) lines.add("岗位文本偏少，打开官网详情后再生成下一轮匹配。");
        if (lines.isEmpty()) lines.add("保持证据更新，投递前再核对最新 JD。");
        return lines;
    }

    private static String grade(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        if (score >= 50) return "E";
        return "F";
    }

    private static int clamp(int score) { return Math.max(0, Math.min(100, score)); }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return "";
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").strip();
    }
}
