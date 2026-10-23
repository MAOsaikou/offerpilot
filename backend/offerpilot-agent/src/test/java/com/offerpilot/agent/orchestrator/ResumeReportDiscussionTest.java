package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.*;
import com.offerpilot.common.error.*;
import com.offerpilot.llm.*;
import com.offerpilot.resume.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeReportDiscussionTest {
    final ObjectMapper json = new ObjectMapper();
    final ResumeDiagnosisService diagnoses = mock(ResumeDiagnosisService.class);
    final ModelGateway gateway = mock(ModelGateway.class);
    final Map<String, Object> context = Map.of("resumeId", "res_1", "resumeVersionId", "ver_historical",
            "diagnosisReportId", "diag_1");

    ExpertRegistry registry() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("diagnoses", new ResumeDiagnosisForChat(diagnoses));
        return new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),
                beans.getBeanProvider(ResumeDiagnosisForChat.class));
    }

    ObjectNode report() throws Exception {
        return (ObjectNode) json.readTree("""
                {"reportId":"diag_1","summary":"需要补充个人贡献与验证依据","findings":[
                  {"findingId":"f1","ruleId":"CONTRIBUTION","priority":"MEDIUM",
                   "suggestion":"补充亲自采取的缓存策略和验证方式","evidence":[{"path":"/summary","quote":"负责缓存优化"}]},
                  {"findingId":"f2","ruleId":"EVIDENCE","priority":"HIGH",
                   "suggestion":"说明测试方法","evidence":[{"path":"/projects/0/description","quote":"完成测试"}]}]}
                """);
    }

    @ParameterizedTest @CsvSource(value = {
            "DEFAULT, advice, 第一条问题具体该怎么改？", "AUTO, advice, 第一条问题具体该怎么改？", "RESUME, advice, 第一条问题具体该怎么改？",
            "DEFAULT, discuss, 这条怎么改？", "AUTO, discuss, 这条怎么改？", "RESUME, discuss, 这条怎么改？",
            "DEFAULT, clarify, 高优先级那条怎么改？", "AUTO, clarify, 高优先级那条怎么改？", "RESUME, clarify, 高优先级那条怎么改？",
            "DEFAULT, diagnose, 第一条问题具体该怎么改？", "AUTO, diagnose, 第一条问题具体该怎么改？", "RESUME, diagnose, 第一条问题具体该怎么改？"
    }, nullValues = "DEFAULT")
    void boundReportOverridesActionClassificationAndFeedsOrderedFindingsToReply(AgentMode mode, String action, String text) throws Exception {
        var report = report();
        when(diagnoses.report("owner", "res_1", "ver_historical", "diag_1")).thenReturn(report);
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"action\":\"" + action + "\",\"question\":\"请说明是哪一条\"}"))))));
        var registry = registry();
        var parser = new ResumeIntentParser(gateway, json, new ByteArrayResource("resume".getBytes(StandardCharsets.UTF_8)));
        var planner = new IntentPlanner(gateway, json, registry, new ByteArrayResource(new byte[0]), null, parser);
        var reply = new ArrayList<Message>();
        var events = new Orchestrator(planner, registry).run(mode, text, List.of(), ModelTier.FLASH,
                "run", new AtomicInteger(), reply, usage -> {}, "owner", context)
                .collectList().block(Duration.ofSeconds(3));

        assertThat(events).noneMatch(AgentEvent.ApprovalRequired.class::isInstance);
        assertThat(events).filteredOn(AgentEvent.PlanCreated.class::isInstance).singleElement().satisfies(event ->
                assertThat(((AgentEvent.PlanCreated) event).steps()).singleElement().satisfies(step ->
                        assertThat(step.agent()).isEqualTo(ExpertType.RESUME)));
        assertThat(events).filteredOn(AgentEvent.ToolCall.class::isInstance).singleElement().satisfies(event -> {
            var tool = (AgentEvent.ToolCall) event;
            assertThat(tool.name()).isEqualTo("diagnoseResume");
            assertThat(tool.access()).isEqualTo(AgentEvent.ToolAccess.READ);
        });
        assertThat(events).filteredOn(AgentEvent.UiCard.class::isInstance).singleElement().satisfies(event ->
                assertThat(((AgentEvent.UiCard) event).cardId()).isEqualTo("diag_1"));
        assertThat(reply.get(reply.size() - 2).getText()).contains("findings原始顺序", "高优先级那条", "不重新诊断");
        assertThat(reply.get(reply.size() - 1).getText()).contains(
                "\"ordinal\":1,\"findingId\":\"f1\",\"ruleId\":\"CONTRIBUTION\"",
                "补充亲自采取的缓存策略和验证方式", "负责缓存优化", "\"ordinal\":2,\"findingId\":\"f2\"", "HIGH");
        // The structured view resolves the first item to f1 even though f2 has higher priority.
        var digest = json.readTree(CardNarrationDigest.of(List.of(new ExpertResult.Card("diag_1", "resume_diagnosis", report))));
        assertThat(digest.get(0).path("findings").get(0).path("findingId").asText()).isEqualTo("f1");
        verify(diagnoses).report("owner", "res_1", "ver_historical", "diag_1");
        verify(diagnoses, never()).analyze(any(), any(), any(), any());
        verify(gateway).stream(any(), anyList(), eq(0.1)); // Action classification only; no routing model.
    }

    @Test void registryAlsoReadsBoundReportForAdviceAndPropagatesOwnershipOrVersionRejection() throws Exception {
        var registry = registry();
        var step = new PlanStep("s1", "文字建议", ExpertType.RESUME, Map.of("action", "advice"), List.of());
        when(diagnoses.report("owner", "res_1", "ver_historical", "diag_1")).thenReturn(report());
        assertThat(registry.diagnosing(step, context)).isTrue();
        assertThat(registry.persistingAnalysis(step, context)).isFalse();
        assertThat(registry.execute("owner", context, "run", step, Map.of()).block().card().cardId()).isEqualTo("diag_1");
        when(diagnoses.report("other-owner", "res_1", "ver_historical", "diag_1"))
                .thenThrow(new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        assertThatThrownBy(() -> registry.execute("other-owner", context, "run", step, Map.of()).block())
                .isInstanceOf(BusinessException.class);
        var wrongVersion = new HashMap<>(context);
        wrongVersion.put("resumeVersionId", "ver_other");
        when(diagnoses.report("owner", "res_1", "ver_other", "diag_1"))
                .thenThrow(new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        assertThatThrownBy(() -> registry.execute("owner", wrongVersion, "run", step, Map.of()).block())
                .isInstanceOf(BusinessException.class);
        verify(diagnoses, never()).analyze(any(), any(), any(), any());
    }
}
