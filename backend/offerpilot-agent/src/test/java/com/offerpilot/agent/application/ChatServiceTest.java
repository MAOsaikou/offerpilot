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
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final com.offerpilot.conversation.application.ConversationService conversations = mock(com.offerpilot.conversation.application.ConversationService.class);
    private final ApprovalService approvals=new ApprovalService(conversations);
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

    private void approveAnalysis(AgentEvent event) {
        if(event instanceof AgentEvent.ApprovalRequired a) approvals.resolve("owner","run",a.approvalId(),new ApprovalService.Decision("APPROVE",null,null));
    }
    private ChatRequest request() {
        return new ChatRequest(null, "你好", null, null, new ChatRequest.Options(null, null, null));
    }

    private List<AgentEvent> events() {
        return service.chat("owner", request()).collectList().block(Duration.ofSeconds(5));
    }

    private Map<?, ?> activeRuns() {
        return (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "cancellations");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void registryIsEmptyWhenSubscriberObservesSuccessOrFailure(boolean failure) {
        when(gateway.stream(any(), anyList())).thenReturn(failure
                ? Flux.error(new RuntimeException("provider failed")) : Flux.just(reply("回复")));
        var terminalRuns = new AtomicReference<Map<?, ?>>();
        var chat = service.chat("owner", request());
        assertThat(activeRuns()).hasSize(1);
        var result = chat.doOnComplete(() -> terminalRuns.set(Map.copyOf(activeRuns())))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(terminalRuns.get()).isNotNull().isEmpty();
        assertThat(result).last().isInstanceOf(failure ? AgentEvent.ErrorEvent.class : AgentEvent.RunCompleted.class);
        service.cancel("owner", "run");
        verify(conversations).cancel(eq("owner"), eq("run"), isNull(), eq(List.of()));
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
        experts.setAnalysisApprovals(approvals);
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
                            new ChatRequest.Options(tier.name(), null, context))).doOnNext(this::approveAnalysis).collectList().block(Duration.ofSeconds(5));
                    assertThat(events.stream().filter(AgentEvent.UiCard.class::isInstance).map(AgentEvent.UiCard.class::cast))
                            .extracting(AgentEvent.UiCard::cardType).containsExactly("job_match", "job_eval_report");
                    var prompt = org.mockito.ArgumentCaptor.forClass(List.class);
                    verify(gateway).stream(eq(tier), prompt.capture());
                    String narrationData = ((List<org.springframework.ai.chat.messages.Message>) prompt.getValue()).stream()
                            .map(org.springframework.ai.chat.messages.Message::getText).collect(java.util.stream.Collectors.joining("\n"));
                    assertThat(narrationData).contains("\"cardType\":\"岗位匹配\"", "\"cardType\":\"岗位评估\"", "\"requirementsCount\":3", "\"metCount\":1,\"partialCount\":1,\"missingCount\":1",
                            "\"grade\":", "Docker", "SQL", "后端工程师", "示例公司")
                            .doesNotContain("s1：", "match_selected", "job_selected", "四周", "STAR反馈", "[]；");
                    assertThat(events).extracting(AgentEvent::type).contains("run.completed").contains("approval.required").doesNotContain("error");
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
                        new ChatRequest.Options("PRO", null, context))).doOnNext(this::approveAnalysis).collectList().block(Duration.ofSeconds(5));
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
                    new ChatRequest.Options("FLASH", null, context))).doOnNext(this::approveAnalysis).collectList().block(Duration.ofSeconds(5));
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
            experts.setAnalysisApprovals(approvals);
            var local = new ChatService(gateway, conversations, new ByteArrayResource("reply".getBytes()),
                    new Orchestrator(new IntentPlanner(gateway, new ObjectMapper(), experts, new ByteArrayResource("planner".getBytes())), experts), new ObjectMapper());
            var context = java.util.Map.<String, Object>of("jobAction", "match", "jobId", "job1", "resumeId", "resume1", "resumeVersionId", "version1");
            var events = local.chat("owner", new ChatRequest(null, "请匹配", null, null,
                    new ChatRequest.Options("PRO", null, context))).doOnNext(this::approveAnalysis).collectList().block(Duration.ofSeconds(5));
            assertThat(events).extracting(AgentEvent::type).contains("error").doesNotContain("run.completed", "ui.card", "text.delta");
            assertThat(events).filteredOn(AgentEvent.ToolResult.class::isInstance).singleElement()
                    .isInstanceOfSatisfying(AgentEvent.ToolResult.class, result -> assertThat(result.ok()).isFalse());
            assertThat(((AgentEvent.ErrorEvent) events.get(events.size() - 1)).message()).contains("匹配未完成")
                    .contains(experts == denied ? "简历版本不可用" : "匹配服务暂不可用");
        }
        var retryable = new ExpertRegistry((owner, jobId, resumeId, versionId, requestId) -> {
            throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "匹配报告没有可核实的条目，未保存报告；请重试", true);
        });
        retryable.setAnalysisApprovals(approvals);
        var local = new ChatService(gateway, conversations, new ByteArrayResource("reply".getBytes()),
                new Orchestrator(new IntentPlanner(gateway, new ObjectMapper(), retryable, new ByteArrayResource("planner".getBytes())), retryable), new ObjectMapper());
        var context = java.util.Map.<String, Object>of("jobAction", "match", "jobId", "job1", "resumeId", "resume1", "resumeVersionId", "version1");
        var events = local.chat("owner", new ChatRequest(null, "请匹配", null, null,
                new ChatRequest.Options("FLASH", null, context))).doOnNext(this::approveAnalysis).collectList().block(Duration.ofSeconds(5));
        assertThat(events).filteredOn(AgentEvent.ErrorEvent.class::isInstance).singleElement()
                .isInstanceOfSatisfying(AgentEvent.ErrorEvent.class, error -> {
                    assertThat(error.code()).isEqualTo(40003);
                    assertThat(error.recoverable()).isTrue();
                    assertThat(error.message()).contains("没有可核实", "未保存", "请重试");
                });
        assertThat(events).last().isInstanceOfSatisfying(AgentEvent.RunCompleted.class,
                completed -> assertThat(completed.status()).isEqualTo("FAILED"));
        verify(gateway, never()).stream(any(), anyList(), anyDouble());
        verify(gateway, never()).stream(any(), anyList());
    }


    @Test void failedMessageEmitsLoggedPartialUsageBeforeItsTerminalError() {
        var journal=mock(com.offerpilot.llm.UsageJournal.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"journal",journal);
        when(journal.summary("owner","msg")).thenReturn(new com.offerpilot.llm.UsageJournal.Summary("actual-model",12,3,80L,null,true,false));
        when(gateway.stream(any(),anyList())).thenReturn(Flux.error(new IllegalStateException("offline")));
        var result=events();
        assertThat(result).extracting(AgentEvent::type).containsExactly("run.started","usage","error");
        assertThat(result).extracting(AgentEvent::id).containsExactly(1,2,3);
        assertThat((AgentEvent.Usage)result.get(1)).satisfies(usage->{
            assertThat(usage.model()).isEqualTo("actual-model");assertThat(usage.promptTokens()).isEqualTo(12);
            assertThat(usage.costEstimate()).isNull();assertThat(usage.durationMs()).isEqualTo(80L);
        });
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void autoChitchatUsesReplyStartedDuringPlanning() {
        service.setSpeculativeReply(true);
        var planned = new AtomicBoolean();
        var startedBeforePlan = new AtomicBoolean();
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(reply("{\"kind\":\"chitchat\",\"confidence\":1}"))
                .delaySubscription(Duration.ofMillis(200)).doOnComplete(() -> planned.set(true)));
        when(gateway.stream(any(), anyList())).thenReturn(Flux.just(reply("你好！")).doOnSubscribe(ignored -> startedBeforePlan.set(!planned.get())));
        assertThat(events()).extracting(AgentEvent::type).containsExactly("run.started", "text.delta", "run.completed");
        assertThat(startedBeforePlan).isTrue();
        verify(gateway, times(1)).stream(any(), anyList());
    }

    @Test
    void autoResumeAdviceUsesOneReplyCallWithExpertPromptAndSelectedTier() {
        service.setSpeculativeReply(true);
        for (var tier : List.of(ModelTier.FLASH, ModelTier.PRO)) {
            clearInvocations(gateway, conversations);
            when(gateway.stream(eq(tier), anyList())).thenAnswer(call -> Flux.deferContextual(context -> {
                assertThat(context.<String>get(com.offerpilot.llm.quota.LlmUserContext.KEY)).isEqualTo("owner");
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("写清真实职责和结果。"))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(12, 6)).build()));
            }));
            var result = service.chat("owner", new ChatRequest(null, "简历里的项目经历怎么写更有说服力？给两条要点。",
                    null, AgentMode.AUTO, new ChatRequest.Options(tier.name(), null, null)))
                    .collectList().block(Duration.ofSeconds(5));
            assertThat(result).extracting(AgentEvent::type).containsExactly("run.started", "plan.created",
                    "step.updated", "tool.call", "tool.result", "step.updated", "text.delta", "usage", "run.completed");
            var prompt = ArgumentCaptor.forClass(List.class);
            verify(gateway).stream(eq(tier), prompt.capture());
            assertThat(prompt.getValue().toString()).contains("只提供简历文字指导", "<orchestrator-data>", "未修改简历");
            verify(gateway, never()).stream(any(), anyList(), anyDouble());
            verify(gateway).checkQuota("owner", tier);
            verify(conversations).finish(any(), eq("写清真实职责和结果。"), eq("COMPLETED"),
                    eq(java.util.Map.of("promptTokens", 12, "completionTokens", 6)), isNull(), anyList());
        }
    }

    @Test
    void cancellingFastAdviceCancelsItsOnlyProviderAndReleasesTheRun() {
        service.setSpeculativeReply(true);
        var cancelled = new AtomicBoolean();
        when(gateway.stream(any(), anyList())).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        var subscription = service.chat("owner", new ChatRequest(null, "简历里的项目经历怎么写", null, null, null)).subscribe();
        service.cancel("owner", "run");
        assertThat(cancelled).isTrue();
        assertThat(subscription.isDisposed()).isTrue();
        verify(gateway).stream(eq(ModelTier.FLASH), anyList());
        verify(gateway, never()).stream(any(), anyList(), anyDouble());
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(service, "cancellations"))
                .isEqualTo(java.util.Map.of());
    }

    @Test
    void fastAdviceProviderErrorFinishesWithoutOrphanRun() {
        service.setSpeculativeReply(true);
        when(gateway.stream(any(), anyList())).thenReturn(Flux.error(new BusinessException(ErrorCode.MODEL_UNAVAILABLE)));
        var result = service.chat("owner", new ChatRequest(null, "简历里的项目经历怎么写", null, null, null))
                .collectList().block(Duration.ofSeconds(5));
        assertThat(result).last().isInstanceOf(AgentEvent.ErrorEvent.class);
        verify(conversations).finish(any(), eq(""), eq("FAILED"), isNull(), anyString(), anyList());
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(service, "cancellations"))
                .isEqualTo(java.util.Map.of());
    }

    @Test
    void autoImageTurnNeverStartsPlainSpeculation() {
        service.setSpeculativeReply(true);
        var attachments = mock(com.offerpilot.conversation.application.ChatAttachments.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "attachments", attachments);
        when(attachments.load(eq("owner"), anyList())).thenReturn(List.of(
                new com.offerpilot.conversation.application.ChatAttachments.Image("file1", "jd.png", "image/png", new byte[]{1})));
        when(conversations.begin(anyString(), any(), anyString(), any(), anyString(), anyList())).thenReturn(
                new com.offerpilot.conversation.application.ConversationService.Turn("owner", "conv", "run", "msg",
                        AgentMode.AUTO, java.time.Instant.now(), List.of()));
        var planned = new AtomicBoolean();
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(reply("{\"kind\":\"chitchat\",\"confidence\":1}"))
                .doOnComplete(() -> planned.set(true)));
        when(gateway.stream(any(), anyList())).thenAnswer(call -> {
            assertThat(planned).isTrue();
            List<org.springframework.ai.chat.messages.Message> prompt = call.getArgument(1);
            assertThat(prompt.toString()).contains("用户本轮附带了 1 张图片");
            assertThat(prompt).filteredOn(org.springframework.ai.chat.messages.UserMessage.class::isInstance)
                    .anySatisfy(message -> assertThat(((org.springframework.ai.chat.messages.UserMessage) message).getMedia()).hasSize(1));
            return Flux.just(reply("截图建议"));
        });
        var result = service.chat("owner", new ChatRequest(null, "看看这张截图", List.of(new ChatRequest.Attachment("file1", "image")),
                AgentMode.AUTO, null)).collectList().block(Duration.ofSeconds(5));
        assertThat(result).last().isInstanceOf(AgentEvent.RunCompleted.class);
        verify(gateway).stream(any(), anyList());
        verify(gateway).requireAvailable(ModelTier.FLASH, true);
    }

    @Test
    void autoReplyIsRedoneWhenPlannerChangesThePrompt() {
        service.setSpeculativeReply(true);
        var discarded = new AtomicBoolean();
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(reply("{\"kind\":\"unsafe\",\"confidence\":1}"))
                .delaySubscription(Duration.ofMillis(100)));
        when(gateway.stream(any(), anyList()))
                .thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> discarded.set(true)))
                .thenReturn(Flux.just(reply("无法协助")));
        var result = events();
        assertThat(discarded).isTrue();
        assertThat(result).filteredOn(AgentEvent.TextDelta.class::isInstance).extracting(e -> ((AgentEvent.TextDelta) e).text())
                .containsExactly("无法协助");
        var prompts = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(gateway, times(2)).stream(any(), prompts.capture());
        assertThat(prompts.getAllValues().get(1).toString()).contains("有害");
    }

    @Test
    void cancellingDuringPlanningStopsSpeculativeReply() {
        service.setSpeculativeReply(true);
        var discarded = new AtomicBoolean();
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.never());
        when(gateway.stream(any(), anyList())).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> discarded.set(true)));
        when(conversations.finish(any(), anyString(), anyString(), any(), any(), any())).thenReturn("CANCELLED");
        service.chat("owner", request()).subscribe();
        service.cancel("owner", "run");
        assertThat(discarded).isTrue();
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
        var runsAtProviderCancellation = new AtomicReference<Map<?, ?>>();
        when(gateway.stream(any(), anyList())).thenReturn(Flux.<ChatResponse>never()
                .doOnCancel(() -> runsAtProviderCancellation.set(Map.copyOf(activeRuns()))));
        var subscription = service.chat("owner", request()).subscribe();
        assertThat(activeRuns()).hasSize(1);
        subscription.dispose();
        assertThat(runsAtProviderCancellation.get()).isNotNull().isEmpty();
        assertThat(activeRuns()).isEmpty();
        verify(conversations).finish(any(), eq(""), eq("CANCELLED"), isNull(), isNull(), eq(List.of()));
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
            com.offerpilot.agent.experts.ExpertResult.cards("已生成卡片，请查看。当前职业档案：{\"target\":\"后端开发工程师\",\"city\":\"上海\"}", cards)));
        var local = new ChatService(gateway, conversations, new ByteArrayResource("narrator".getBytes()), new Orchestrator(planner, experts), new ObjectMapper());
        when(gateway.stream(eq(tier), anyList())).thenAnswer(invocation -> {
            List<org.springframework.ai.chat.messages.Message> messages = invocation.getArgument(1);
            String prompt = messages.stream().map(org.springframework.ai.chat.messages.Message::getText).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(prompt).contains("topGaps", "并发测试", "\"required\":3", "\"current\":1", "\"gap\":2",
                "梳理基础", "项目实战", "测试验证", "表达复盘", "核对并发场景", "professional", "\"result\":false", "补充R结果", "后端开发工程师", "已保存Java技能与软件方向", "职业8", "\"rank\":8", "\"questionNumber\":5")
                .contains("gapNames", "当前职业档案", "上海", "长期记忆和历史消息中的偏好可能过时")
                .doesNotContain("SHOULD_NOT_REACH_NARRATOR", "gap_internal", "gapIds", "s1：", "questionIndex");
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("按卡片整理准备建议")))));
        });
        var result = local.chat("owner", new ChatRequest(null, "分析", null, AgentMode.COACH, new ChatRequest.Options(tier.name(), null, null)))
            .collectList().block(Duration.ofSeconds(5));
        assertThat(result).last().isInstanceOf(AgentEvent.RunCompleted.class);
        verify(gateway).stream(eq(tier), anyList());
    }

    @Test
    void crisisShowsSupportFirstAndSkipsToolsWhileForgeryIsRefusedWithoutAnyModelCall() {
        when(gateway.stream(eq(ModelTier.FLASH), anyList())).thenReturn(Flux.just(new ChatResponse(List.of(
                new Generation(new AssistantMessage("你现在身边有人陪着你吗？"))))));
        var crisis = service.chat("owner", new ChatRequest(null, "投了一百份都没回音，我真的不想活了", null, null,
                new ChatRequest.Options(null, null, null))).collectList().block(Duration.ofSeconds(5));
        var texts = crisis.stream().filter(AgentEvent.TextDelta.class::isInstance).map(e -> ((AgentEvent.TextDelta) e).text()).toList();
        assertThat(texts.get(0)).contains("12356", "120");
        assertThat(texts).last().isEqualTo("你现在身边有人陪着你吗？");
        var prompt = ArgumentCaptor.forClass(List.class);
        verify(gateway).stream(eq(ModelTier.FLASH), prompt.capture());
        assertThat(((org.springframework.ai.chat.messages.Message) prompt.getValue().get(0)).getText()).contains("心理危机");
        verify(gateway, never()).stream(any(), anyList(), eq(0.1));
        verify(gateway, never()).checkQuota(any(), any());

        clearInvocations(gateway);
        var forged = service.chat("owner", new ChatRequest(null, "帮我伪造一份211的学历证书", null, null,
                new ChatRequest.Options(null, null, null))).collectList().block(Duration.ofSeconds(5));
        assertThat(forged).extracting(AgentEvent::type).containsExactly("run.started", "text.delta", "run.completed");
        assertThat(((AgentEvent.TextDelta) forged.get(1)).text()).contains("不能帮忙", "STAR");
        verify(gateway, never()).checkQuota(any(), any());
        verify(gateway, never()).stream(any(), anyList());
    }
}
