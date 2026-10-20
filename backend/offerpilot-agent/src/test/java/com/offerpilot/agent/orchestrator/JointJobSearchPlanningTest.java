package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.job.application.JobSearchForChat;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.llm.quota.LlmUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.*;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JointJobSearchPlanningTest {
    static final String PREFIX = "{\"kind\":\"single\",\"expert\":\"JOB\",\"confidence\":1,\"needsClarification\":false,";
    static final String SEARCH = "{\"action\":\"search\",\"query\":\"Java\",\"site\":\"\",\"nature\":\"intern\",\"location\":\"上海\"}";
    final ModelGateway gateway = mock(ModelGateway.class);
    final JobSearchForChat searches = mock(JobSearchForChat.class);
    final ObjectMapper json = new ObjectMapper();
    final org.springframework.beans.factory.support.StaticListableBeanFactory beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    final ExpertRegistry experts = new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),
            beans.getBeanProvider(com.offerpilot.resume.application.ResumeDiagnosisForChat.class), searches);

    IntentPlanner planner() throws Exception {
        var jobs = new JobIntentParser(gateway, json, new ByteArrayResource("action prompt".getBytes()));
        var planner = new IntentPlanner(gateway, json, experts, new ByteArrayResource("planner".getBytes()), jobs,
                null, new ByteArrayResource("joint search contract".getBytes()));
        planner.setRuleFirstRouting(false); // Exercise the model path independently of the new rule route.
        return planner;
    }

    ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 2)).build());
    }

    @Test void completesSearchWithOnePlanningCallPreservingTierOwnerHistoryAndUsage() throws Exception {
        for (var tier : List.of(ModelTier.FLASH, ModelTier.PRO)) {
            clearInvocations(gateway, searches);
            var usage = new AtomicInteger();
            when(gateway.stream(eq(tier), anyList(), eq(0.1))).thenAnswer(call -> Flux.deferContextual(context -> {
                assertThat(context.<String>get(LlmUserContext.KEY)).isEqualTo("owner");
                return Flux.just(chunk(PREFIX + "\"jobSearch\":" + SEARCH + ",\"plan\":null}"));
            }));
            when(searches.search("Java", "", "intern", "上海")).thenReturn(json.createObjectNode().put("returnedCount", 0));
            var reply = new ArrayList<Message>();
            var planner = planner();
            // Joint decoding works independently of speculative expert handoff.
            if (tier == ModelTier.PRO) planner.setEarlyExpertHandoff(false);
            var events = new Orchestrator(planner, experts).run(AgentMode.AUTO, "换成上海", List.of(new UserMessage("找Java实习")),
                            tier, "run", new AtomicInteger(), reply, u -> usage.addAndGet(u.getTotalTokens()), "owner",
                            Map.of("jobId", "old-job", "resumeId", "old-resume", "resumeVersionId", "old-version"))
                    .contextWrite(c -> LlmUserContext.withUser(c, "owner"))
                    .collectList().block(Duration.ofSeconds(2));
            assertThat(events).filteredOn(AgentEvent.ToolCall.class::isInstance).singleElement().satisfies(event -> {
                var tool = (AgentEvent.ToolCall) event;
                assertThat(tool.name()).isEqualTo("search_jobs");
                assertThat(tool.access()).isEqualTo(AgentEvent.ToolAccess.READ);
                assertThat(tool.args().toString()).doesNotContain("old-job", "old-resume", "old-version");
            });
            assertThat(events).filteredOn(AgentEvent.UiCard.class::isInstance).extracting(e -> ((AgentEvent.UiCard) e).cardType()).containsExactly("job_card", "campus_calendar");
            assertThat(events).noneMatch(AgentEvent.TextDelta.class::isInstance);
            assertThat(reply).isNotEmpty();
            assertThat(usage).hasValue(12);
            verify(searches).search("Java", "", "intern", "上海");
            verify(gateway).stream(eq(tier), argThat(messages -> messages.get(0).getText().contains("joint search contract")
                    && messages.stream().anyMatch(m -> m.getText().equals("找Java实习"))), eq(0.1));
            verifyNoMoreInteractions(gateway);
        }
    }

    @Test void routeCancelsOrdinaryPrefetchButNoActionOrToolStartsBeforeCompletePlan() throws Exception {
        var tail = Sinks.<ChatResponse>one();
        var cancelled = new AtomicBoolean();
        var dropped = new AtomicBoolean();
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(
                Flux.just(chunk(PREFIX)).concatWith(tail.asMono()).doOnCancel(() -> cancelled.set(true)));
        var subscription = new Orchestrator(planner(), experts).run(AgentMode.AUTO, "找上海Java实习", List.of(), ModelTier.FLASH,
                        "run", new AtomicInteger(), new ArrayList<>(), ignored -> {}, "owner", Map.of())
                .contextWrite(c -> c.put(IntentPlanner.ON_EXPERT_ROUTE, (Runnable) () -> dropped.set(true))).subscribe();
        assertThat(dropped).isTrue();
        verifyNoInteractions(searches);
        verify(gateway).stream(any(), anyList(), eq(0.1));
        verifyNoMoreInteractions(gateway);
        subscription.dispose();
        assertThat(cancelled).isTrue();
        tail.tryEmitValue(chunk("\"jobSearch\":" + SEARCH + "}"));
        verifyNoInteractions(searches);
    }

    @Test void disabledSwitchUsesOriginalActionParserAndOmitsJointPrompt() throws Exception {
        var planner = planner();
        planner.setJointJobSearchPlanning(false);
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(
                Flux.just(chunk(PREFIX + "\"jobSearch\":" + SEARCH + "}")),
                Flux.just(chunk(SEARCH.replace("上海", "北京"))));
        var decision = planner.prepare(AgentMode.AUTO, "找Java", List.of(), ModelTier.FLASH, ignored -> {}, Map.of())
                .block(Duration.ofSeconds(2));
        assertThat(decision.plan().steps().get(0).inputs()).containsEntry("location", "北京");
        verify(gateway, times(2)).stream(any(), argThat(messages ->
                !messages.get(0).getText().contains("joint search contract")), eq(0.1));
    }

    @Test void missingMalformedOrUnsafeSearchDataFallsBackWithoutExecutingIt() throws Exception {
        for (String candidate : List.of("null", "[]", "\"search\"", "{}",
                SEARCH.replace("Java", ""), SEARCH.replace("intern", "unknown"), SEARCH.replace("\"Java\"", "123"),
                SEARCH.replace("\"Java\"", "true"), SEARCH.replace("\"Java\"", "[]"),
                SEARCH.replace("Java", "x".repeat(101)), SEARCH.replace("上海", "x".repeat(61)),
                SEARCH.replace("search", "match"), SEARCH.replace("search", "createApplication"),
                SEARCH.replace("search", "updateApplicationStatus"),
                SEARCH.replace("{", "{\"jobId\":\"guessed\","), SEARCH.replace("{", "{\"note\":\"write\","))) {
            clearInvocations(gateway);
            when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(
                    Flux.just(chunk(PREFIX + "\"jobSearch\":" + candidate + "}")),
                    Flux.just(chunk("{\"action\":\"clarify\",\"question\":\"请确认方向\"}")));
            var decision = planner().prepare(AgentMode.AUTO, "找岗位", List.of(), ModelTier.FLASH, ignored -> {}, Map.of())
                    .block(Duration.ofSeconds(2));
            assertThat(decision.needsClarification()).as(candidate).isTrue();
            assertThat(decision.plan()).isNull();
            verify(gateway, times(2)).stream(any(), anyList(), eq(0.1));
        }
        verifyNoInteractions(searches);
    }

    @Test void compositeInputsAndOtherActionsStillRequireActionParser() throws Exception {
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(chunk("""
                {"kind":"composite","confidence":1,"jobSearch":%s,"plan":{"goal":"搜索再匹配","steps":[
                {"id":"s1","title":"搜索","agent":"JOB","inputs":%s,"dependsOn":[]}]}}
                """.formatted(SEARCH, SEARCH))), Flux.just(chunk("{\"action\":\"clarify\",\"question\":\"请选择岗位\"}")));
        assertThat(planner().prepare(AgentMode.AUTO, "搜索再匹配", List.of(), ModelTier.FLASH, ignored -> {}, Map.of())
                .block(Duration.ofSeconds(2)).needsClarification()).isTrue();
        verify(gateway, times(2)).stream(any(), anyList(), eq(0.1));
        verifyNoInteractions(searches);
    }

    @Test void validParametersNeverOverrideClarificationOrAuthorizeMalformedPlannerTail() throws Exception {
        for (String output : List.of(PREFIX + "\"jobSearch\":" + SEARCH + "} {}",
                PREFIX.replace("false", "true") + "\"clarifyingQuestion\":\"确认搜索条件？\",\"jobSearch\":" + SEARCH + "}")) {
            clearInvocations(gateway);
            when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(chunk(output)),
                    Flux.just(chunk("{\"kind\":\"chitchat\",\"confidence\":1}")));
            var events = new Orchestrator(planner(), experts).run(AgentMode.AUTO, "找岗位", List.of(), ModelTier.FLASH,
                    "run", new AtomicInteger(), new ArrayList<>(), ignored -> {}, "owner", Map.of())
                    .collectList().block(Duration.ofSeconds(2));
            assertThat(events).isEmpty();
            verifyNoInteractions(searches);
        }
    }
}
