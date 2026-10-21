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
    @Test void practiceRequestsStartAnInterviewInsteadOfContinuingAMissingSession() {
        // Live 2026-10-21: "我想练习 Java 后端面试，先问我一道题。" fell through to continueInterview and failed with 30701.
        assertThat(prepare("我想练习 Java 后端面试，先问我一道题。", Map.of()).inputs())
                .containsEntry("action", "startInterview").containsEntry("role", "Java 后端").doesNotContainKey("answer");
        assertThat(prepare("帮我练练产品经理面试", Map.of()).inputs()).containsEntry("action", "startInterview").containsEntry("role", "产品经理");
        assertThat(prepare("来一场前端面试，先问我一道题", Map.of()).inputs()).containsEntry("action", "startInterview");
        assertThat(prepare("准备一下数据分析岗位面试", Map.of()).inputs()).containsEntry("action", "startInterview").containsEntry("role", "数据分析");
        // Answers that merely mention practice or interviews stay answers.
        assertThat(prepare("我在大学里练习过很多次面试，所以表达比较流畅", Map.of()).inputs()).containsEntry("action", "continueInterview");
        assertThat(prepare("面试官问我为什么离职时，我会先说明项目结束", Map.of()).inputs()).containsEntry("action", "continueInterview");
    }

    @Test void historicalNoteQuestionsAreReadOnlyButActualAnswersStillContinue() {
        for (String text : List.of("根据我的笔记，我上次面试缓存击穿是怎么答的？还要怎么改进？", "我笔记里记的缓存击穿解决方案有哪些？", "热点 key 过期导致数据库被打爆，我之前记过怎么处理吗？")) {
            assertThat(InterviewIntentParser.noteReviewRequest(text)).isTrue();
            var proposedWrite = new PlanStep("s1","面试",ExpertType.INTERVIEWER,Map.of("action","continueInterview"),List.of());
            assertThat(InterviewIntentParser.prepare(proposedWrite,text,Map.of()).inputs()).containsEntry("action","noteGuidance").doesNotContainKeys("answer","sessionId");
            assertThat(prepare(text,Map.of("interviewAction","continueInterview","interviewSessionId","iv_1")).inputs())
                    .containsEntry("action","continueInterview").containsEntry("sessionId","iv_1");
        }
        for (String text : List.of("我参考笔记里的互斥锁方案回答，遇到竞争会短暂重试", "我的笔记记录了如何用 SETNX 重建缓存", "我参考笔记回答如何处理缓存击穿，先讲互斥锁", "我想练习 Java 后端面试，先问我一道题。", "下一题", "给我这场面试打分"))
            assertThat(InterviewIntentParser.noteReviewRequest(text)).isFalse();
    }
}
