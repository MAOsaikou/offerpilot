package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.orchestrator.IntentPlanner;
import com.offerpilot.agent.orchestrator.Orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    void streamsTextThenUsageThenCompletionWithIncreasingIds() {
        when(gateway.stream(eq(ModelTier.FLASH), anyList())).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("你好")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("，同学"))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(12, 6)).build())));
        var events = events();
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly("run.started", "text.delta", "text.delta", "usage", "run.completed");
        assertThat(events).extracting(AgentEvent::id).containsExactly(1, 2, 3, 4, 5);
        assertThat((AgentEvent.Usage) events.get(3)).extracting(AgentEvent.Usage::promptTokens,
                AgentEvent.Usage::completionTokens).containsExactly(12, 6);
        verify(gateway).stream(eq(ModelTier.FLASH), argThat(messages -> messages.size() == 2
                && messages.get(0).getText().equals("test system prompt") && messages.get(1).getText().equals("你好")));
    }

    @Test
    void systemPromptCarriesCurrentDate() throws Exception {
        var dated = new ChatService(gateway, conversations, new ByteArrayResource(
                "今天是 {current_date}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), orchestrator, new ObjectMapper());
        when(gateway.stream(eq(ModelTier.FLASH), anyList())).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("好"))))));

        dated.chat("owner", request()).collectList().block(Duration.ofSeconds(5));

        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        verify(gateway).stream(eq(ModelTier.FLASH),
                argThat(messages -> messages.get(0).getText().equals("今天是 " + today)));
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

    @Test
    void acceptedCardsAreStreamedAndSavedAndInvalidCardsAreDropped() throws Exception {
        Orchestrator cards = mock(Orchestrator.class);
        when(cards.run(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Flux.just(
                new AgentEvent.UiCard("ui.card", 0, "run", java.time.Instant.now(), "s1", "card_job_1", "job_match", java.util.Map.of("score", 80)),
                new AgentEvent.UiCard("ui.card", 0, "run", java.time.Instant.now(), "s1", "card_job_1", "job_match", java.util.Map.of("score", 1)),
                new AgentEvent.UiCard("ui.card", 0, "run", java.time.Instant.now(), "s1", "card_bad", "not_a_card", java.util.Map.of("score", 1))));
        var local = new ChatService(gateway, conversations, new ByteArrayResource("test system prompt".getBytes()), cards, new ObjectMapper());
        when(gateway.stream(eq(ModelTier.FLASH), anyList())).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("报告"))))));

        var events = local.chat("owner", request()).collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(AgentEvent::type).containsExactly("run.started", "ui.card", "text.delta", "run.completed");
        assertThat(((AgentEvent.UiCard) events.get(1)).cardId()).isEqualTo("card_job_1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageCard>> saved = ArgumentCaptor.forClass(List.class);
        verify(conversations).finish(any(), eq("报告"), eq("COMPLETED"), isNull(), isNull(), saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(card -> {
            assertThat(card.cardId()).isEqualTo("card_job_1");
            assertThat(card.cardType()).isEqualTo("job_match");
            assertThat(card.payload().path("score").asInt()).isEqualTo(80);
        });
    }

    @Test
    void suppliedContextMatchIsPersistedAsJobMatchWithoutSnapshots() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ExpertRegistry experts = new ExpertRegistry((owner, jobId, resumeId, versionId, requestId) -> {
            assertThat(List.of(owner, jobId, resumeId, versionId)).containsExactly("owner", "job_1", "res_1", "ver_1");
            assertThat(requestId).matches("[a-zA-Z0-9-]{16,80}");
            ObjectNode report = json.createObjectNode().put("id", "match_01abc").put("jobId", "job_1")
                    .put("score", 80).put("summary", "证据可用");
            report.set("requirements", json.createArrayNode().add(json.createObjectNode()
                    .put("requirement", "Java").put("status", "MET").put("resumeQuote", "做过 Java").put("suggestion", "保留")));
            report.set("jobSnapshot", json.createObjectNode().put("title", "不能进卡片"));
            return JobMatchCards.fromReport(report);
        });
        Orchestrator real = new Orchestrator(new IntentPlanner(gateway, json, experts, new ByteArrayResource("planner".getBytes())), experts);
        ChatService local = new ChatService(gateway, conversations, new ByteArrayResource("test system prompt".getBytes()), real, json);
        when(conversations.begin(anyString(), any(), anyString(), any(), anyString())).thenReturn(
                new com.offerpilot.conversation.application.ConversationService.Turn("owner", "conv", "run", "msg",
                        AgentMode.JOB, java.time.Instant.now(), List.of()));
        when(gateway.stream(eq(ModelTier.FLASH), anyList())).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("报告已给出"))))));

        var request = new ChatRequest(null, "对照这份简历", null, AgentMode.JOB, new ChatRequest.Options(null, null, java.util.Map.of(
                "jobId", "job_1", "resumeId", "res_1", "resumeVersionId", "ver_1")));
        var events = local.chat("owner", request).collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(AgentEvent::type)
                .containsExactly("run.started", "plan.created", "step.updated", "tool.call", "tool.result", "ui.card", "step.updated", "text.delta", "run.completed");
        assertThat(((AgentEvent.UiCard) events.get(5)).cardId()).isEqualTo("match_01abc");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageCard>> saved = ArgumentCaptor.forClass(List.class);
        verify(conversations).finish(any(), eq("报告已给出"), eq("COMPLETED"), isNull(), isNull(), saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(card -> {
            assertThat(card.cardId()).isEqualTo("match_01abc");
            assertThat(card.cardType()).isEqualTo("job_match");
            assertThat(card.payload().path("score").asInt()).isEqualTo(80);
            assertThat(card.payload().path("matched").get(0).path("evidence").asText()).isEqualTo("做过 Java");
            assertThat(card.payload().has("jobSnapshot")).isFalse();
            assertThat(card.payload().has("dimensions")).isFalse();
        });
        verify(gateway).stream(eq(ModelTier.FLASH), argThat(messages -> messages.stream()
                .anyMatch(message -> message.getText().contains("结果已作为卡片展示") && !message.getText().contains("未执行真实业务操作"))));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void narratorReceivesStructuredCoachInterviewAndCareerCards(ModelTier tier) throws Exception {
        var cards = List.of(
            new com.offerpilot.agent.experts.ExpertResult.Card("gap", "skill_gap", java.util.Map.of(
                "target", java.util.Map.of("title", "后端开发"), "skills", List.of(java.util.Map.of("name", "并发测试", "required", 3, "current", 1, "gap", 2, "learnFirst", true)),
                "privateField", "SHOULD_NOT_REACH_NARRATOR")),
            new com.offerpilot.agent.experts.ExpertResult.Card("roadmap", "roadmap", java.util.Map.of("weeks", List.of(
                java.util.Map.of("index", 1, "theme", "梳理基础", "items", List.of(java.util.Map.of("title", "核对并发场景"))),
                java.util.Map.of("index", 2, "theme", "项目实战", "items", List.of()),
                java.util.Map.of("index", 3, "theme", "测试验证", "items", List.of()),
                java.util.Map.of("index", 4, "theme", "表达复盘", "items", List.of())))),
            new com.offerpilot.agent.experts.ExpertResult.Card("interview", "interview_report", java.util.Map.of(
                "overall", 60, "dimensions", java.util.Map.of("professional", 65, "expression", 60, "logic", 55, "jobFit", 60),
                "weaknesses", List.of("R 结果证据不足"), "advice", List.of("补充可核对结果"),
                "turns", List.of(java.util.Map.of("question", "介绍项目", "score", 60, "star", java.util.Map.of("situation", true, "task", true, "action", true, "result", false), "feedback", "补充R结果")))),
            new com.offerpilot.agent.experts.ExpertResult.Card("career", "career_recommendation", java.util.Map.of(
                "hollandCode", "RIS", "recommendations", List.of(java.util.Map.of("name", "后端开发工程师", "family", "软件研发", "hollandCode", "ICR", "similarity", .42, "reasons", List.of("已保存Java技能与软件方向"))))));
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
                "梳理基础", "项目实战", "测试验证", "表达复盘", "核对并发场景", "professional", "\"result\":false", "补充R结果", "后端开发工程师", "已保存Java技能与软件方向")
                .doesNotContain("SHOULD_NOT_REACH_NARRATOR");
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("按卡片整理准备建议")))));
        });
        var result = local.chat("owner", new ChatRequest(null, "分析", null, AgentMode.COACH, new ChatRequest.Options(tier.name(), null, null)))
            .collectList().block(Duration.ofSeconds(5));
        assertThat(result).last().isInstanceOf(AgentEvent.RunCompleted.class);
        verify(gateway).stream(eq(tier), anyList());
    }
}
