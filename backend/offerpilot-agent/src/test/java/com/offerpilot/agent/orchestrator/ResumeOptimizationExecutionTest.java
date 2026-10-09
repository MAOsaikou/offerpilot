package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.job.application.*;
import com.offerpilot.resume.application.*;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResumeOptimizationExecutionTest {
    final ModelGateway gateway = mock(ModelGateway.class);
    final ResumeOptimizationForChat optimizations = mock(ResumeOptimizationForChat.class);
    final ResumeDiagnosisForChat diagnoses = mock(ResumeDiagnosisForChat.class);
    final JobDescriptionForChat descriptions = mock(JobDescriptionForChat.class);
    final JobSearchForChat searches = mock(JobSearchForChat.class);
    final JobMatchForChat matches = mock(JobMatchForChat.class);
    final ObjectMapper json = new ObjectMapper();
    final Map<String,Object> selected = Map.of("jobId", "job_1", "resumeId", "res_1", "resumeVersionId", "ver_1");
    final AtomicReference<String> action = new AtomicReference<>("optimize");
    final List<Message> reply = new ArrayList<>();
    String plan = "{\"kind\":\"single\",\"expert\":\"RESUME\",\"confidence\":1}";
    Orchestrator orchestrator() throws Exception {
        var beans = new StaticListableBeanFactory(); beans.addBean("matches", matches); beans.addBean("diagnoses", diagnoses);
        var experts = new ExpertRegistry(beans.getBeanProvider(JobMatchForChat.class), beans.getBeanProvider(ResumeDiagnosisForChat.class), searches, optimizations, descriptions);
        when(gateway.stream(any(), anyList(), eq(0.1))).thenAnswer(call -> {
            List<Message> messages = call.getArgument(1);
            String value = messages.get(0).getText().equals("plan") ? plan : "{\"action\":\"" + action.get() + "\"}";
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(value)))));
        });
        when(descriptions.description("job_1")).thenReturn("岗位JD");
        var report = json.createObjectNode().put("reportId", "opt_1").put("resumeId", "res_1").put("resumeVersionId", "ver_1");
        report.putArray("patches");
        when(optimizations.generate(eq("owner"), eq("res_1"), eq("ver_1"), eq("岗位JD"), anyString())).thenReturn(Mono.just(report));
        when(optimizations.report("owner", "res_1", "ver_1", "opt_1")).thenReturn(Mono.just(report));
        return new Orchestrator(new IntentPlanner(gateway, json, experts, resource("plan"),
                new JobIntentParser(gateway, json, resource("job")), new ResumeIntentParser(gateway, json, resource("resume"))), experts);
    }
    ByteArrayResource resource(String text) { return new ByteArrayResource(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    Flux<AgentEvent> run(Orchestrator orchestrator, AgentMode mode, Map<String,Object> context) {
        return orchestrator.run(mode, "针对岗位改写", List.of(), ModelTier.FLASH, "run_123", new AtomicInteger(), reply, ignored -> {}, "owner", context);
    }
    @Test void autoResumeAndJobModesGenerateReadOnlyCardsWithExplicitSelection() throws Exception {
        var orchestrator = orchestrator();
        for (var mode : List.of(AgentMode.AUTO, AgentMode.RESUME, AgentMode.JOB)) {
            var events = run(orchestrator, mode, selected).collectList().block(Duration.ofSeconds(5));
            assertThat(events).filteredOn(AgentEvent.UiCard.class::isInstance).singleElement().satisfies(e -> {
                assertThat(((AgentEvent.UiCard)e).cardType()).isEqualTo("resume_diff");
                assertThat(((AgentEvent.UiCard)e).cardId()).isEqualTo("opt_1");
            });
            assertThat(events).filteredOn(AgentEvent.ToolCall.class::isInstance).singleElement().satisfies(e -> {
                assertThat(((AgentEvent.ToolCall)e).name()).isEqualTo("optimizeResumeForJob");
                assertThat(((AgentEvent.ToolCall)e).access()).isEqualTo(AgentEvent.ToolAccess.READ);
            });
            assertThat(reply.get(reply.size()-2)).isInstanceOfSatisfying(SystemMessage.class,
                    message -> assertThat(message.getText()).contains("只读动作未执行采纳", "逐条核对"));
        }
        verify(optimizations, times(3)).generate(eq("owner"), eq("res_1"), eq("ver_1"), eq("岗位JD"), matches("[a-z0-9-]{36}"));
        verifyNoInteractions(diagnoses, matches, searches);
    }
    @Test void missingSelectionAndInvalidActionsNeverGenerateOrGuessIds() throws Exception {
        var orchestrator = orchestrator();
        for (var context : List.<Map<String,Object>>of(Map.of(), Map.of("jobId", "job_1"), Map.of("resumeId", "res_1", "resumeVersionId", "ver_1")))
            assertThat(run(orchestrator, AgentMode.RESUME, context).collectList().block()).isEmpty();
        action.set("optimize\",\"resumeId\":\"guessed");
        assertThat(run(orchestrator, AgentMode.RESUME, selected).collectList().block()).isEmpty();
        verifyNoInteractions(descriptions, optimizations, diagnoses);
    }
    @Test void compositeDiscardsModelResourceIdsAndUsesCallerSelection() throws Exception {
        var orchestrator = orchestrator();
        plan = "{\"kind\":\"composite\",\"confidence\":1,\"plan\":{\"goal\":\"改写\",\"steps\":[{\"id\":\"s1\",\"title\":\"改写\",\"agent\":\"RESUME\",\"inputs\":{\"resumeId\":\"guessed\",\"resumeVersionId\":\"guessed\",\"jobId\":\"guessed\"},\"dependsOn\":[]}]}}";
        var events = run(orchestrator, AgentMode.AUTO, selected).collectList().block(Duration.ofSeconds(5));
        assertThat(events.toString()).doesNotContain("guessed");
        verify(optimizations).generate(eq("owner"), eq("res_1"), eq("ver_1"), eq("岗位JD"), anyString());
    }
    @Test void discussionReadsExistingReportWithoutJobReadOrRegenerationAndAdviceNeverAdopts() throws Exception {
        var orchestrator = orchestrator(); action.set("discuss");
        var context = Map.<String,Object>of("resumeId", "res_1", "resumeVersionId", "ver_1", "optimizationReportId", "opt_1");
        assertThat(run(orchestrator, AgentMode.RESUME, context).collectList().block()).anyMatch(AgentEvent.UiCard.class::isInstance);
        verify(optimizations).report("owner", "res_1", "ver_1", "opt_1");
        verify(optimizations, never()).generate(any(), any(), any(), any(), any());
        action.set("advice");
        assertThat(run(orchestrator, AgentMode.RESUME, context).collectList().block()).noneMatch(AgentEvent.UiCard.class::isInstance);
        assertThat(reply.get(reply.size()-2)).isInstanceOfSatisfying(SystemMessage.class,
                message -> assertThat(message.getText()).contains("只提供简历文字指导"));
        verifyNoInteractions(descriptions, diagnoses, matches, searches);
    }
    @Test void failuresAreSanitizedAndCancellationPropagatesToRewrite() throws Exception {
        var orchestrator = orchestrator();
        when(optimizations.generate(any(), any(), any(), any(), any())).thenReturn(Mono.error(new RuntimeException("private-secret")));
        var events = run(orchestrator, AgentMode.RESUME, selected).collectList().block();
        assertThat(events).noneMatch(AgentEvent.UiCard.class::isInstance);
        assertThat(events.toString()).contains("FAILED").doesNotContain("private-secret");
        var cancelled = new AtomicBoolean(); var subscribed = new AtomicBoolean();
        when(optimizations.generate(any(), any(), any(), any(), any())).thenReturn(Mono.<com.fasterxml.jackson.databind.JsonNode>never()
                .doOnSubscribe(s -> subscribed.set(true)).doOnCancel(() -> cancelled.set(true)));
        var subscription = run(orchestrator, AgentMode.RESUME, selected).subscribe();
        long deadline = System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while (!subscribed.get() && System.nanoTime()<deadline) Thread.sleep(10);
        assertThat(subscribed).isTrue(); subscription.dispose(); assertThat(cancelled).isTrue();
    }
    @Test void diagnosisAndDiscussionKeepExistingDiagnosisContextInsteadOfGeneratingRewrite() throws Exception {
        var orchestrator = orchestrator();
        var context = Map.<String,Object>of("resumeId", "res_1", "resumeVersionId", "ver_1", "diagnosisReportId", "diag_1");
        when(diagnoses.execute(eq("owner"), eq("res_1"), eq("ver_1"), eq("diag_1"), anyString()))
                .thenReturn(Mono.just(json.createObjectNode().put("reportId", "diag_1")));
        for (String value : List.of("diagnose", "discuss")) {
            action.set(value);
            assertThat(run(orchestrator, AgentMode.RESUME, context).collectList().block()).anySatisfy(e -> {
                assertThat(e).isInstanceOf(AgentEvent.UiCard.class);
                assertThat(((AgentEvent.UiCard)e).cardType()).isEqualTo("resume_diagnosis");
            });
        }
        verifyNoInteractions(optimizations, descriptions, matches, searches);
    }
}
