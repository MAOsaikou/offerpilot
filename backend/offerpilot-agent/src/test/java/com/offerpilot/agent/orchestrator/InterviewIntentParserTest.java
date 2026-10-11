package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class InterviewIntentParserTest {
    PlanStep prepare(String text, Map<String, Object> context) {
        return InterviewIntentParser.prepare(new PlanStep("s1", "面试", ExpertType.INTERVIEWER,
                Map.of("sessionId", "model_guessed"), List.of()), text, context);
    }
    @Test void explicitStepActionsArePreservedButGuessedResourceIdsAreRemoved() {
        var step = new PlanStep("s2", "评分", ExpertType.INTERVIEWER, Map.of("action", "generateReport", "sessionId", "guessed"), List.of());
        assertThat(InterviewIntentParser.prepare(step, "开始面试并评分", Map.of()).inputs())
                .containsEntry("action", "scoreInterview").doesNotContainKey("sessionId");
    }
    @Test void actionsAndRoleComeFromUserAndContextInsteadOfModelIds() {
        for (String text : List.of("帮我模拟面试", "给我做一场文字面试", "面试练习"))
            assertThat(prepare(text, Map.of()).inputs()).containsEntry("action", "startInterview").containsEntry("kind", "项目与经历").doesNotContainKey("sessionId");
        for (String text : List.of("STAR 评价", "给我面试反馈", "评价刚才的面试", "给我这场面试的评分。", "请给我这场面试打分", "给我分数", "评价一下我的表现", "帮我对这场面试打分", "请给这场面试打分", "为这场面试评分"))
            assertThat(prepare(text, Map.of()).inputs()).containsEntry("action", "scoreInterview");
        assertThat(InterviewIntentParser.prepare(new PlanStep("s1", "继续", ExpertType.INTERVIEWER,
                Map.of("action", "continueInterview"), List.of()), "给我这场面试的评分。", Map.of("interviewSessionId", "iv_1")).inputs())
                .containsAllEntriesOf(Map.of("action", "scoreInterview", "sessionId", "iv_1")).doesNotContainKey("answer");
        assertThat(prepare("不要给我评分，我补充一下刚才的回答", Map.of()).inputs()).containsEntry("action", "continueInterview");
        assertThat(prepare("模拟面试，岗位：Java工程师", Map.of()).inputs()).containsEntry("role", "Java工程师");
        assertThat(prepare("面试：产品经理", Map.of("interviewAction", "startInterview")).inputs()).containsEntry("role", "产品经理");
        assertThat(prepare("模拟一场面试", Map.of()).inputs()).containsEntry("role", "目标岗位");
        assertThat(prepare("帮我模拟Java后端工程师面试", Map.of()).inputs()).containsEntry("role", "Java后端工程师").containsEntry("action", "startInterview");
        assertThat(prepare("我在项目中搭建模拟面试系统，测试后将任务标记完成", Map.of()).inputs()).containsEntry("action", "continueInterview");
        assertThat(prepare("我参考已入库的 STAR 项目故事组织表达，最终完成答辩", Map.of()).inputs())
                .containsEntry("action", "continueInterview");
        assertThat(prepare("我分析用户反馈，最终提升评分20%", Map.of()).inputs()).containsEntry("action", "continueInterview");
        assertThat(prepare("结束面试并给我报告", Map.of()).inputs()).containsEntry("action", "completeInterview");
        assertThat(prepare("下一题", Map.of("interviewSessionId", "iv_1")).inputs()).containsEntry("nextQuestion", true).containsEntry("sessionId", "iv_1");
        assertThat(prepare("结束面试 iv_2", Map.of()).inputs()).containsEntry("action", "completeInterview").containsEntry("sessionId", "iv_2");
    }
}
