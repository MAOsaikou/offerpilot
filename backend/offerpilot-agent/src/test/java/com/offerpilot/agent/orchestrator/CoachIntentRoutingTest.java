package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.llm.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoachIntentRoutingTest {
    @ParameterizedTest @ValueSource(strings = {
            "帮我规划下学期的学习", "帮我做一个学习计划", "下学期学什么", "下学期该学哪些课程？",
            "安排一下本学期的学习", "帮我规划学习路线", "我想制定英语备考计划", "帮我规划考研备考",
            "制定期末复习计划", "给我一份学业规划", "根据面试反馈制定学习计划"})
    void autoStudyPlansRouteToCoachWithoutModelClassification(String text) throws Exception {
        var gateway = mock(ModelGateway.class);
        var planner = new IntentPlanner(gateway, new ObjectMapper(), new ExpertRegistry(), new ByteArrayResource(new byte[0]));
        // Both a fresh request and a request with history/selected resources use the same fallback rule.
        for (var context : List.<Map<String, Object>>of(Map.of(), Map.of("jobId", "job_1"))) {
            var decision = planner.prepare(AgentMode.AUTO, text,
                    context.isEmpty() ? List.of() : List.of(new UserMessage("我想提升自己")),
                    ModelTier.FLASH, usage -> fail("No routing model expected"), context).block();
            assertThat(decision.kind()).isEqualTo("single");
            assertThat(decision.expert()).isEqualTo(ExpertType.COACH);
            assertThat(decision.plan().steps()).singleElement().satisfies(step ->
                    assertThat(step.inputs()).containsEntry("action", "planLearning").containsEntry("noteQuery", text));
        }
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest @CsvSource({
            "帮我做职业规划, CAREER_DISCOVERY", "我需要生涯规划, CAREER_DISCOVERY",
            "帮我规划职业发展, CAREER_DISCOVERY", "帮我找学习计划相关岗位, JOB",
            "帮我推荐学习规划相关工作, JOB", "帮我模拟面试, INTERVIEWER",
            "安排面试练习, INTERVIEWER", "帮我制定面试计划, INTERVIEWER"})
    void careerJobAndInterviewRequestsRemainWithTheClassifier(String text, ExpertType expert) throws Exception {
        assertThat(CoachIntentParser.matches(text)).isFalse();
        var gateway = mock(ModelGateway.class);
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"kind\":\"single\",\"expert\":\"" + expert + "\",\"confidence\":1}"))))));
        var planner = new IntentPlanner(gateway, new ObjectMapper(), new ExpertRegistry(), new ByteArrayResource(new byte[0]));
        assertThat(planner.prepare(AgentMode.AUTO, text, List.of(), ModelTier.FLASH, usage -> {}, Map.of())
                .block().expert()).isEqualTo(expert);
        verify(gateway).stream(any(), anyList(), eq(0.1));
    }

    @ParameterizedTest @ValueSource(strings = {"今天学习很开心", "什么是学习率", "规划下学期的旅行", "不要制定学习计划",
            "不需要备考计划", "探索职业然后制定学习计划", "帮我写简历然后规划学习", ""})
    void ordinaryQuestionsNegationsAndCompositeTasksDoNotMatchCoachRule(String text) {
        assertThat(CoachIntentParser.matches(text)).isFalse();
    }
}
