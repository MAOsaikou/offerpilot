package com.offerpilot.interview.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import java.util.*;

public record InterviewOptions(String resumeId, String versionId, String difficulty, String mode, int durationMinutes) {
    public static InterviewOptions defaults() { return new InterviewOptions(null, null, "STANDARD", "TEXT", 30); }
    public void validate() {
        if (!Set.of("EASY", "STANDARD", "HARD").contains(Objects.toString(difficulty, ""))
                || !Set.of("TEXT", "VOICE").contains(Objects.toString(mode, ""))
                || !Set.of(15, 30, 45, 60).contains(durationMinutes)
                || (resumeId == null) != (versionId == null)
                || (resumeId != null && (!resumeId.matches("\\S{1,80}") || !versionId.matches("\\S{1,80}"))))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请检查简历版本、难度、模式和时长（15/30/45/60分钟）。");
    }
    public int questionCount() { return switch (durationMinutes) { case 15 -> 3; case 45 -> 8; case 60 -> 10; default -> 5; }; }
    public List<String> questions(String role, String kind, String resumeContext) {
        var technical = List.of("介绍一个关键技术的工作原理和适用场景。", "如何设计并验证系统的性能和可靠性？", "如何定位线上故障并避免再次发生？", "比较两种技术方案，说明取舍依据。", "如何测试关键边界条件？");
        var behavioral = List.of("分享一次协作冲突，你如何达成共识？", "描述一次失败以及你采取的改进。", "在资源不足时如何确定优先级？", "分享你主动承担责任的经历。", "你如何处理不确定的需求？");
        var hr = List.of("为什么选择这个岗位和职业方向？", "你对团队和工作环境有哪些期待？", "如何安排未来一年的职业成长？", "如何看待工作压力与时间管理？", "你的优势与需要改进的地方是什么？");
        List<String> bank = switch (kind) { case "技术" -> technical; case "行为" -> behavioral; case "HR" -> hr; default -> List.of(technical.get(0), behavioral.get(0), hr.get(0), technical.get(1), behavioral.get(1), hr.get(1)); };
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
