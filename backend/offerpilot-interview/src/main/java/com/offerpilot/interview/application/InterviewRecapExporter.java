package com.offerpilot.interview.application;

import org.springframework.stereotype.Service;

/** Builds a take-home markdown recap: Q&A plus local STAR/dimension rubric (not a hiring prediction). */
@Service
public class InterviewRecapExporter {
    private final InterviewService sessions;

    public InterviewRecapExporter(InterviewService sessions) {
        this.sessions = sessions;
    }

    public String markdown(String owner, String id) {
        var session = sessions.get(owner, id);
        var report = InterviewRubric.score(session);
        var out = new StringBuilder();
        out.append("# ").append(safe(session.role())).append(" · 面试复盘包\n\n");
        out.append("- 练习类型：").append(safe(session.kind())).append('\n');
        out.append("- 会话状态：").append(session.completed() ? "已完成" : "进行中快照").append('\n');
        out.append("- 综合线索分：").append(report.overall()).append(" / 100\n");
        out.append("- 维度：专业 ").append(report.dimensions().professional())
                .append(" · 表达 ").append(report.dimensions().expression())
                .append(" · 逻辑 ").append(report.dimensions().logic())
                .append(" · 岗位关联 ").append(report.dimensions().jobFit()).append("\n\n");
        out.append("> ").append(safe(report.note())).append("\n\n");

        out.append("## 优势线索\n\n");
        for (String item : report.strengths()) out.append("- ").append(safe(item)).append('\n');
        out.append("\n## 待补充线索\n\n");
        for (String item : report.weaknesses()) out.append("- ").append(safe(item)).append('\n');
        out.append("\n## 练习建议\n\n");
        for (String item : report.advice()) out.append("- ").append(safe(item)).append('\n');
        if (!report.recommendedLearning().isEmpty()) {
            out.append("\n## 建议练习主题\n\n");
            for (var learn : report.recommendedLearning()) {
                out.append("- ").append(safe(learn.topic())).append("（约 ")
                        .append(learn.estimatedHours()).append(" 小时）：")
                        .append(safe(learn.reason())).append('\n');
            }
        }

        out.append("\n## 逐题复盘\n");
        int index = 1;
        for (var turn : report.turns()) {
            out.append("\n### 第 ").append(index++).append(" 题（线索分 ")
                    .append(turn.score()).append("）\n\n");
            out.append("**题目**\n\n").append(safe(turn.question())).append("\n\n");
            out.append("**回答摘要**\n\n")
                    .append(turn.answerSummary() == null || turn.answerSummary().isBlank()
                            ? "未作答" : safe(turn.answerSummary()))
                    .append("\n\n");
            var star = turn.star();
            out.append("**STAR 线索**\n\n");
            out.append("- S 背景：").append(star.situation() ? "有" : "未见").append('\n');
            out.append("- T 任务：").append(star.task() ? "有" : "未见").append('\n');
            out.append("- A 行动：").append(star.action() ? "有" : "未见").append('\n');
            out.append("- R 结果：").append(star.result() ? "有" : "未见").append("\n\n");
            out.append("**反馈**\n\n").append(safe(turn.feedback())).append("\n\n");
            out.append("**改进提纲**\n\n");
            for (String line : turn.betterAnswerOutline()) out.append("1. ").append(safe(line)).append('\n');
        }

        out.append("\n## 原始完整回答\n");
        index = 1;
        for (var answer : session.answers()) {
            out.append("\n### 第 ").append(index++).append(" 题原文\n\n");
            out.append("**").append(safe(answer.questionText())).append("**\n\n");
            String text = answer.answerText();
            out.append(text == null || text.isBlank() ? "未作答" : text.strip()).append('\n');
        }
        out.append('\n');
        return out.toString();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replace("\r\n", "\n").strip();
    }
}
