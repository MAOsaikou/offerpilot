package com.offerpilot.interview.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import java.util.*;

public record InterviewOptions(String resumeId, String versionId, String difficulty, String mode, int durationMinutes, int questionCount, Map<String, Integer> typeMix,
                               String industry, String feedbackMode) {
    public static final Set<String> TYPES = Set.of("BEHAVIORAL", "SITUATIONAL", "KNOWLEDGE", "CASE");
    public InterviewOptions {
        if (questionCount == 0) questionCount = switch (durationMinutes) { case 15 -> 3; case 45 -> 8; case 60 -> 10; default -> 5; };
        if (typeMix != null && typeMix.values().stream().anyMatch(Objects::isNull)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        typeMix = typeMix == null ? Map.of() : Map.copyOf(typeMix);
        industry = industry == null ? "" : industry.strip();
        feedbackMode = feedbackMode == null ? "IMMEDIATE" : feedbackMode;
    }
    public InterviewOptions(String resumeId, String versionId, String difficulty, String mode, int durationMinutes) {
        this(resumeId, versionId, difficulty, mode, durationMinutes, 0, Map.of(), "", "IMMEDIATE");
    }
    public static InterviewOptions defaults() { return new InterviewOptions(null, null, "STANDARD", "TEXT", 30); }
    public void validate() {
        if (!Set.of("EASY", "STANDARD", "HARD").contains(Objects.toString(difficulty, ""))
                || !Set.of("TEXT", "VOICE").contains(Objects.toString(mode, ""))
                || !Set.of(15, 30, 45, 60).contains(durationMinutes)
                || questionCount < 3 || questionCount > 15
                || industry.length() > 100 || !Set.of("IMMEDIATE", "END").contains(feedbackMode)
                || !TYPES.containsAll(typeMix.keySet())
                || typeMix.values().stream().anyMatch(n -> n < 0 || n > 15)
                || (!typeMix.isEmpty() && typeMix.values().stream().mapToInt(Integer::intValue).sum() != questionCount)
                || (resumeId == null) != (versionId == null)
                || (resumeId != null && (!resumeId.matches("\\S{1,80}") || !versionId.matches("\\S{1,80}"))))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请检查简历版本、难度、题数（3–15）、题型数量总和、反馈方式和时长。");
    }
    public List<String> questionTypes() {
        var result = new ArrayList<String>();
        if (!typeMix.isEmpty()) {
            for (String type : List.of("BEHAVIORAL", "SITUATIONAL", "KNOWLEDGE", "CASE"))
                for (int i=0; i<typeMix.getOrDefault(type,0); i++) result.add(type);
        } else for (int i=0; i<questionCount; i++) result.add(List.of("BEHAVIORAL", "SITUATIONAL", "KNOWLEDGE", "CASE").get(i%4));
        return List.copyOf(result);
    }
    /** Role-neutral offline practice; never implies model personalization. */
    public List<String> fallbackQuestions(String role) {
        var result = new ArrayList<String>();
        var scenarios = List.of("日常工作", "首次接手任务", "信息不完整", "时间紧迫", "资源不足",
                "跨团队协作", "服务对象提出异议", "工作交接", "发现操作失误", "要求发生变化",
                "多个任务冲突", "出现安全或合规风险", "效果不达预期", "需要向非专业人员解释", "复盘与持续改进");
        for (String type : questionTypes()) {
            String base = switch(type) {
                case "BEHAVIORAL" -> "分享一次真实的协作或困难经历，说明你的职责、行动和结果。";
                case "SITUATIONAL" -> "如果工作目标与资源发生冲突，你会如何判断优先级并沟通？";
                case "KNOWLEDGE" -> "选择本岗位的一项核心专业知识，解释适用条件、限制和验证方式。";
                default -> "面对效果未达预期的工作案例，你会如何收集证据、分析原因并制定方案？";
            };
            result.add("面向「"+role+"」，围绕「"+scenarios.get(result.size())+"」："+base
                +(difficulty.equals("EASY") ? "从基础概念和一个具体例子展开。" : difficulty.equals("HARD") ? "分析复杂约束、替代方案与风险边界。" : "说明判断依据和具体步骤。"));
        }
        return List.copyOf(result);
    }
    public List<String> questions(String role, String kind, String resumeContext) {
        var professional = List.of("介绍本岗位一项核心专业知识及其适用场景。", "如何规划并验证本岗位工作的质量与可靠性？", "如何发现工作中的异常并按岗位规范处理，避免再次发生？", "比较本岗位两种专业处理方案，说明取舍依据。", "如何识别并核验本岗位工作的关键风险？");
        var behavioral = List.of("分享一次协作冲突，你如何达成共识？", "描述一次失败以及你采取的改进。", "在资源不足时如何确定优先级？", "分享你主动承担责任的经历。", "你如何处理不确定的需求？");
        var hr = List.of("为什么选择这个岗位和职业方向？", "你对团队和工作环境有哪些期待？", "如何安排未来一年的职业成长？", "如何看待工作压力与时间管理？", "你的优势与需要改进的地方是什么？");
        List<String> bank = switch (kind) { case "技术" -> professional; case "行为" -> behavioral; case "HR" -> hr; default -> List.of(professional.get(0), behavioral.get(0), hr.get(0), professional.get(1), behavioral.get(1), hr.get(1)); };
        String level = switch (difficulty) { case "EASY" -> "从基础概念和一个具体例子展开。"; case "HARD" -> "深入分析约束、替代方案、失败边界及可量化的验证方法。"; default -> "说明你的具体行动、方案取舍和验证结果。"; };
        var result = new ArrayList<String>();
        for (int i=0; i<questionCount(); i++) {
            String question = bank.get(i % bank.size());
            if (i >= bank.size()) question = "换一个不同的真实案例：" + question;
            result.add("面向「" + role + "」：" + question + level
                    + (resumeContext == null || resumeContext.isBlank() ? "" : "结合所选简历中的「" + resumeContext + "」作答。"));
        }
        return List.copyOf(result);
    }
}
