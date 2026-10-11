package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.orchestrator.IntentPlanner;
import com.offerpilot.agent.orchestrator.Orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.conversation.application.MessageCard;
import com.offerpilot.job.application.JobMatchCards;
import org.mockito.ArgumentCaptor;
import com.offerpilot.agent.web.ChatRequest;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final com.offerpilot.conversation.application.ConversationService conversations = mock(com.offerpilot.conversation.application.ConversationService.class);
    private ChatService service;
    private Orchestrator orchestrator;

    @BeforeEach
    void setup() throws Exception {
        var experts = new ExpertRegistry();
        orchestrator = new Orchestrator(new IntentPlanner(gateway, new ObjectMapper(), experts,
                new ByteArrayResource("planner".getBytes())), experts);
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"kind\":\"chitchat\",\"confidence\":1}"))))));
        var json = new ObjectMapper();
        service = new ChatService(gateway, conversations, new ByteArrayResource("test system prompt".getBytes()), orchestrator, json);
        when(conversations.begin(anyString(), any(), anyString(), any(), anyString())).thenReturn(
                new com.offerpilot.conversation.application.ConversationService.Turn("owner", "conv", "run", "msg",
                        com.offerpilot.agent.domain.AgentMode.AUTO, java.time.Instant.now(), List.of()));
        when(conversations.checkpoint(any(), anyString())).thenReturn(true);
        when(conversations.finish(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(gateway.defaultTier()).thenReturn(ModelTier.FLASH);
        when(gateway.modelName(any())).thenReturn("deepseek-v4-flash");
    }

    private ChatRequest request() {
        return new ChatRequest(null, "你好", null, null, new ChatRequest.Options(null, null, null));
    }

    private List<AgentEvent> events() {
        return service.chat("owner", request()).collectList().block(Duration.ofSeconds(5));
    }

    @Test
    void explicitMatchingIgnoresClarificationVariantAndEmptyModelPlansForBothTiers() throws Exception {
        var json = new ObjectMapper();
        var matches = mock(com.offerpilot.job.application.JobMatchForChat.class);
        var report = json.createObjectNode().put("id", "match_selected").put("score", 80).put("summary", "已核实所选match_selected报告");
        report.set("jobSnapshot", json.readTree("{\"id\":\"job_selected\",\"title\":\"后端工程师\",\"company\":\"示例公司\"}"));
        report.set("requirements", json.readTree("""
                [{"status":"MET","requirement":"Java","resumeQuote":"Java 项目"},
                 {"status":"PARTIAL","requirement":"SQL","reason":"缺结果证据","suggestion":"补充查询优化结果"},
                 {"status":"MISSING","requirement":"Docker","reason":"简历未体现","suggestion":"补真实容器项目"}]
                """));
        org.mockito.Mockito.doCallRealMethod().when(matches).match(anyString(), anyString(), anyString(), anyString(), anyString(), any(ModelTier.class));
        when(matches.match(eq("owner"), eq("job_selected"), eq("resume_selected"), eq("version_selected"), anyString()))
                .thenReturn(JobMatchCards.fromReport(report));
        var experts = new ExpertRegistry(matches);
        var parser = new com.offerpilot.agent.orchestrator.JobIntentParser(gateway, json, new ByteArrayResource("job actions".getBytes()));
        var planner = new IntentPlanner(gateway, json, experts, new ByteArrayResource("planner".getBytes()), parser);
        var local = new ChatService(gateway, conversations, new ByteArrayResource("reply".getBytes()), new Orchestrator(planner, experts), json);
        for (var tier : List.of(ModelTier.FLASH, ModelTier.PRO)) {
            for (var mode : List.of(com.offerpilot.agent.domain.AgentMode.AUTO, com.offerpilot.agent.domain.AgentMode.JOB)) {
                for (String output : List.of(
                        "{\"kind\":\"single\",\"expert\":\"JOB\",\"confidence\":1,\"needsClarification\":true,\"clarifyingQuestion\":\"请提供简历\"}",
                        "{\"kind\":\"composite\",\"confidence\":1,\"plan\":{\"goal\":\"匹配\",\"steps\":[{\"id\":\"s1\",\"title\":\"匹配\",\"agent\":\"JOB\",\"inputs\":{\"action\":\"match_resume_to_job\",\"jobId\":\"model_guessed\"},\"dependsOn\":[]}]}}",
                        "{\"kind\":\"composite\",\"confidence\":1,\"plan\":{\"goal\":\"匹配\",\"steps\":[]}}")) {
                    clearInvocations(gateway, conversations, matches);
                    when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output))))));
                    when(gateway.stream(eq(tier), anyList())).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("匹配卡已展示"))))));
                    when(conversations.begin(anyString(), any(), anyString(), any(), anyString())).thenReturn(
                            new com.offerpilot.conversation.application.ConversationService.Turn("owner", "conv", "run", "msg", mode, java.time.Instant.now(), List.of()));
                    var context = java.util.Map.<String, Object>of("jobAction", "match", "jobId", "job_selected",
                            "resumeId", "resume_selected", "resumeVersionId", "version_selected");
                    var events = local.chat("owner", new ChatRequest(null, "请匹配这个岗位与我的简历", null, mode,
                            new ChatRequest.Options(tier.name(), null, context))).collectList().block(Duration.ofSeconds(5));
                    assertThat(events.stream().filter(AgentEvent.UiCard.class::isInstance).map(AgentEvent.UiCard.class::cast))
                            .extracting(AgentEvent.UiCard::cardType).containsExactly("job_match", "job_eval_report");
                    var prompt = org.mockito.ArgumentCaptor.forClass(List.class);
                    verify(gateway).stream(eq(tier), prompt.capture());
                    String narrationData = ((List<org.springframework.ai.chat.messages.Message>) prompt.getValue()).stream()
                            .map(org.springframework.ai.chat.messages.Message::getText).collect(java.util.stream.Collectors.joining("\n"));
                    assertThat(narrationData).contains("\"cardType\":\"岗位匹配\"", "\"cardType\":\"岗位评估\"", "\"requirementsCount\":3", "\"metCount\":1,\"partialCount\":1,\"missingCount\":1",
                            "\"grade\":", "Docker", "SQL", "后端工程师", "示例公司")
                            .doesNotContain("s1：", "match_selected", "job_selected", "四周", "STAR反馈", "[]；");
                    assertThat(events).extracting(AgentEvent::type).contains("run.completed").doesNotContain("error", "approval.required");
                    verify(gateway, never()).stream(any(), anyList(), eq(0.1));
                    verify(matches).match("owner", "job_selected", "resume_selected", "version_selected", ExpertRegistry.requestId("run", "s1"), tier);
                    verify(matches).match("owner", "job_selected", "resume_selected", "version_selected", ExpertRegistry.requestId("run", "s1"));
                    verify(conversations).finish(any(), eq("匹配卡已展示"), eq("COMPLETED"), any(), isNull(), argThat(cards -> cards.size() == 2));
                }
            }
        }
    }

    @Test
    void oversizedMatchCardIsVisibleErrorInsteadOfSilentCompletion() throws Exception {
        var cards = mock(Orchestrator.class);
        when(cards.run(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Flux.just(
                new AgentEvent.UiCard("ui.card", 2, "run", java.time.Instant.now(), "s1", "match_large", "job_match",
                        java.util.Map.of("summary", "证".repeat(32768)))));
        var local = new ChatService(gateway, conversations, new ByteArrayResource("reply".getBytes()), cards, new ObjectMapper());
        var events = local.chat("owner", request()).collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(AgentEvent::type).containsExactly("run.started", "error");
        assertThat(((AgentEvent.ErrorEvent) events.get(1)).message()).contains("匹配报告", "展示");
        verify(gateway, never()).stream(any(), anyList());
        verify(conversations).finish(any(), anyString(), eq("FAILED"), any(), anyString(), argThat(saved -> saved.isEmpty()));
    }

    @Test
    void selectedResourcesWithModelClarificationOrFallbackCannotCompleteSilently() throws Exception {
        var json = new ObjectMapper();
        var parser = new com.offerpilot.agent.orchestrator.JobIntentParser(gateway, json, new ByteArrayResource("actions".getBytes()));
        var experts = new ExpertRegistry();
        var local = new ChatService(gateway, conversations, new ByteArrayResource("reply".getBytes()),
                new Orchestrator(new IntentPlanner(gateway, json, experts, new ByteArrayResource("planner".getBytes()), parser), experts), json);
        for (var mode : List.of(com.offerpilot.agent.domain.AgentMode.AUTO, com.offerpilot.agent.domain.AgentMode.JOB)) {
            for (String output : List.of("not JSON", mode == com.offerpilot.agent.domain.AgentMode.JOB
                    ? "{\"action\":\"clarify\",\"question\":\"请选择岗位与简历\"}"
                    : "{\"kind\":\"single\",\"expert\":\"JOB\",\"confidence\":1,\"needsClarification\":true,\"clarifyingQuestion\":\"请选择岗位与简历\"}")) {
                when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output))))));
                when(conversations.begin(anyString(), any(), anyString(), any(), anyString())).thenReturn(
                        new com.offerpilot.conversation.application.ConversationService.Turn("owner", "conv", "run", "msg", mode, java.time.Instant.now(), List.of()));
                var context = java.util.Map.<String, Object>of("jobId", "job1", "resumeId", "resume1", "resumeVersionId", "version1");
                var events = local.chat("owner", new ChatRequest(null, "请匹配这个岗位与我的简历", null, mode,
                        new ChatRequest.Options("PRO", null, context))).collectList().block(Duration.ofSeconds(5));
                assertThat(events).extracting(AgentEvent::type).containsExactly("run.started", "error");
                assertThat(((AgentEvent.ErrorEvent) events.get(1)).message()).contains("未执行任务", "在聊天中匹配");
            }
        }
        verify(gateway, never()).stream(any(), anyList());
    }

    @Test
    void incompleteOrConflictingExplicitMatchingShowsErrorWithoutCallingAnyModel() {
        for (var context : List.of(
                java.util.Map.<String, Object>of("jobAction", "match", "jobId", "job1", "resumeId", "resume1"),
                java.util.Map.<String, Object>of("jobAction", "match", "jobId", "job1", "resumeId", "resume1", "resumeVersionId", " "),
                java.util.Map.<String, Object>of("jobAction", "match", "jobId", 42, "resumeId", "resume1", "resumeVersionId", "version1"),
                java.util.Map.<String, Object>of("jobAction", "match", "jobId", "x".repeat(81), "resumeId", "resume1", "resumeVersionId", "version1"),
                java.util.Map.<String, Object>of("jobAction", "match", "boardAction", "createApplication"),
                java.util.Map.<String, Object>of("jobAction", "match_job"))) {
            var events = service.chat("owner", new ChatRequest(null, "匹配", null, null,
                    new ChatRequest.Options("FLASH", null, context))).collectList().block(Duration.ofSeconds(5));
            assertThat(events).extracting(AgentEvent::type).containsExactly("run.started", "error");
            assertThat(((AgentEvent.ErrorEvent) events.get(1)).message()).contains("匹配");
        }
        verify(gateway, never()).stream(any(), anyList(), anyDouble());
        verify(gateway, never()).stream(any(), anyList());
    }

    @Test
    void unavailableOrRejectedExplicitMatchHasFailedToolAndVisibleErrorWithoutNarration() throws Exception {
        var denied = new ExpertRegistry((owner, jobId, resumeId, versionId, requestId) -> {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历版本不可用");
        });
        for (var experts : List.of(new ExpertRegistry(), denied)) {
            var local = new ChatService(gateway, conversations, new ByteArrayResource("reply".getBytes()),
                    new Orchestrator(new IntentPlanner(gateway, new ObjectMapper(), experts, new ByteArrayResource("planner".getBytes())), experts), new ObjectMapper());
            var context = java.util.Map.<String, Object>of("jobAction", "match", "jobId", "job1", "resumeId", "resume1", "resumeVersionId", "version1");
            var events = local.chat("owner", new ChatRequest(null, "请匹配", null, null,
                    new ChatRequest.Options("PRO", null, context))).collectList().block(Duration.ofSeconds(5));
            assertThat(events).extracting(AgentEvent::type).contains("error").doesNotContain("run.completed", "ui.card", "text.delta");
            assertThat(events).filteredOn(AgentEvent.ToolResult.class::isInstance).singleElement()
                    .isInstanceOfSatisfying(AgentEvent.ToolResult.class, result -> assertThat(result.ok()).isFalse());
            assertThat(((AgentEvent.ErrorEvent) events.get(events.size() - 1)).message()).contains("匹配未完成")
                    .contains(experts == denied ? "简历版本不可用" : "匹配服务暂不可用");
        }
        verify(gateway, never()).stream(any(), anyList(), anyDouble());
        verify(gateway, never()).stream(any(), anyList());
    }


    @Test
    void missingKeyProducesActionableTerminalError() {
        when(gateway.stream(any(), anyList())).thenThrow(new BusinessException(ErrorCode.MODEL_NOT_CONFIGURED));
        assertThat(events()).last().isInstanceOfSatisfying(AgentEvent.ErrorEvent.class, e -> {
            assertThat(e.code()).isEqualTo(40009);
            assertThat(e.recoverable()).isFalse();
        });
    }

    @Test
    void providerErrorAfterPartialOutputNeverLeaksDetailsOrReportsSuccess() {
        when(gateway.stream(any(), anyList())).thenReturn(Flux.concat(
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("部分回复"))))),
                Flux.error(new RuntimeException("secret-key private provider body"))));
        var result = events();
        assertThat(result).extracting(AgentEvent::type).containsExactly("run.started", "text.delta", "error");
        assertThat(result.toString()).doesNotContain("secret-key", "private provider body");
    }

    @Test
    void timeoutAndEmptyResponseAreFailures() {
        when(gateway.stream(any(), anyList())).thenReturn(Flux.error(new TimeoutException()));
        assertThat(((AgentEvent.ErrorEvent) events().get(events().size() - 1)).code()).isEqualTo(40001);
        when(gateway.stream(any(), anyList())).thenReturn(Flux.empty());
        assertThat(((AgentEvent.ErrorEvent) events().get(events().size() - 1)).code()).isEqualTo(40010);
    }

    @Test
    void cancellingDownstreamCancelsProvider() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(gateway.stream(any(), anyList())).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        var subscription = service.chat("owner", request()).subscribe();
        subscription.dispose();
        assertThat(cancelled).isTrue();
    }

    @Test
    void cancellationDuringPlanningStopsProviderAndCompletesAsCancelled() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        when(conversations.finish(any(), anyString(), anyString(), any(), any(), any())).thenReturn("CANCELLED");
        var received = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();
        service.chat("owner", request()).subscribe(received::add);
        service.cancel("owner", "run");
        assertThat(cancelled).isTrue();
        assertThat(received).extracting(AgentEvent::type).containsExactly("run.started", "run.completed");
        assertThat(((AgentEvent.RunCompleted)received.get(1)).status()).isEqualTo("CANCELLED");
        verify(gateway, never()).stream(any(), anyList());
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void narratorReceivesStructuredCoachInterviewAndCareerCards(ModelTier tier) throws Exception {
        var cards = List.of(
            new com.offerpilot.agent.experts.ExpertResult.Card("gap", "skill_gap", java.util.Map.of(
                "target", java.util.Map.of("title", "后端开发"), "skills", List.of(java.util.Map.of("id", "gap_internal", "name", "并发测试", "required", 3, "current", 1, "gap", 2, "learnFirst", true)),
                "privateField", "SHOULD_NOT_REACH_NARRATOR")),
            new com.offerpilot.agent.experts.ExpertResult.Card("roadmap", "roadmap", java.util.Map.of("weeks", List.of(
                java.util.Map.of("index", 1, "gapIds", List.of("gap_internal"), "theme", "梳理基础", "items", List.of(java.util.Map.of("title", "核对并发场景"))),
                java.util.Map.of("index", 2, "theme", "项目实战", "items", List.of()),
                java.util.Map.of("index", 3, "theme", "测试验证", "items", List.of()),
                java.util.Map.of("index", 4, "theme", "表达复盘", "items", List.of())))),
            new com.offerpilot.agent.experts.ExpertResult.Card("interview", "interview_report", java.util.Map.of(
                "overall", 60, "dimensions", java.util.Map.of("professional", 65, "expression", 60, "logic", 55, "jobFit", 60),
                "weaknesses", List.of("R 结果证据不足"), "advice", List.of("补充可核对结果"),
                "turns", List.of(java.util.Map.of("question", "介绍项目", "score", 60, "star", java.util.Map.of("situation", true, "task", true, "action", true, "result", false), "feedback", "补充R结果")))),
            new com.offerpilot.agent.experts.ExpertResult.Card("career", "career_recommendation", java.util.Map.of(
                "hollandCode", "RIS", "recommendations", java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(rank -> java.util.Map.of(
                    "name", rank == 1 ? "后端开发工程师" : "职业" + rank, "family", "软件研发", "hollandCode", "ICR", "similarity", .42,
                    "reasons", List.of("已保存Java技能与软件方向"))).toList())),
            new com.offerpilot.agent.experts.ExpertResult.Card("state", "interviewer_state", java.util.Map.of(
                "questionIndex", 4, "total", 5, "question", "未来一年如何学习？")));
        var planner = mock(IntentPlanner.class);
        var experts = mock(ExpertRegistry.class);
        var step = new com.offerpilot.agent.domain.PlanStep("s1", "成长", com.offerpilot.agent.domain.ExpertType.COACH, java.util.Map.of(), List.of());
        when(planner.prepare(any(), anyString(), anyList(), any(), any(), anyMap())).thenReturn(reactor.core.publisher.Mono.just(
            new IntentPlanner.Decision("single", step.agent(), 1.0, false, null, new com.offerpilot.agent.domain.Plan("成长", List.of(step)))));
        when(experts.execute(anyString(), anyMap(), anyString(), any(), anyMap())).thenReturn(reactor.core.publisher.Mono.just(
            com.offerpilot.agent.experts.ExpertResult.cards("已生成卡片，请查看", cards)));
        var local = new ChatService(gateway, conversations, new ByteArrayResource("narrator".getBytes()), new Orchestrator(planner, experts), new ObjectMapper());
        when(gateway.stream(eq(tier), anyList())).thenAnswer(invocation -> {
            List<org.springframework.ai.chat.messages.Message> messages = invocation.getArgument(1);
            String prompt = messages.stream().map(org.springframework.ai.chat.messages.Message::getText).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(prompt).contains("topGaps", "并发测试", "\"required\":3", "\"current\":1", "\"gap\":2",
                "梳理基础", "项目实战", "测试验证", "表达复盘", "核对并发场景", "professional", "\"result\":false", "补充R结果", "后端开发工程师", "已保存Java技能与软件方向", "职业8", "\"rank\":8", "\"questionNumber\":5")
                .contains("gapNames").doesNotContain("SHOULD_NOT_REACH_NARRATOR", "gap_internal", "gapIds", "s1：", "questionIndex");
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("按卡片整理准备建议")))));
        });
        var result = local.chat("owner", new ChatRequest(null, "分析", null, AgentMode.COACH, new ChatRequest.Options(tier.name(), null, null)))
            .collectList().block(Duration.ofSeconds(5));
        assertThat(result).last().isInstanceOf(AgentEvent.RunCompleted.class);
        verify(gateway).stream(eq(tier), anyList());
    }
}
