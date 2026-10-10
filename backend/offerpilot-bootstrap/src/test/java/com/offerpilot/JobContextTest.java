package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.application.ApprovalService;
import com.offerpilot.agent.application.BoardWriteTools;
import com.offerpilot.agent.application.ChatService;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.orchestrator.IntentPlanner;
import com.offerpilot.agent.orchestrator.JobIntentParser;
import com.offerpilot.agent.orchestrator.Orchestrator;
import com.offerpilot.agent.web.ChatRequest;
import com.offerpilot.artifact.JobArtifactBridge;
import com.offerpilot.board.application.ApplicationService;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.job.application.JobService;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real provenance bridge and orchestration; only database/provider IO is stubbed. */
class JobContextTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JobService jobs = mock(JobService.class);
    private final JobArtifactBridge artifacts = new JobArtifactBridge(jdbc, jobs);

    @Test void missingAndForeignSourcesDoNotAbortOrdinaryChatOrSelectAJob() {
        var registry = new ExpertRegistry();
        registry.setArtifacts(artifacts);
        for (String key : List.of("applicationId", "matchId", "jobMatchId")) {
            for (String id : List.of("missing", "foreign")) {
                var context = Map.<String,Object>of(key, id, "resumeId", "resume_selected");
                var planner = mock(IntentPlanner.class);
                when(planner.prepare(any(), anyString(), anyList(), any(), any(), anyMap())).thenAnswer(call -> {
                    Map<String,Object> selected = call.getArgument(5);
                    assertThat(selected).doesNotContainKey("jobId").containsEntry("resumeId", "resume_selected");
                    return Mono.just(new IntentPlanner.Decision("chitchat", null, 1.0, false, null, null));
                });
                assertThat(run(new Orchestrator(planner, registry), context)).isEmpty();
                assertThat(context).containsEntry(key, id); // Caller context is not mutated.
            }
        }
        for (String table : List.of("applications", "job_matches")) {
            for (String id : List.of("missing", "foreign"))
                verify(jdbc, atLeastOnce()).queryForList("SELECT job_id FROM " + table + " WHERE user_id=? AND id=?", String.class, "owner", id);
        }
    }

    @Test void ownedSourcesAndSharedJobsResolveBeforePlanning() {
        var registry = new ExpertRegistry();
        registry.setArtifacts(artifacts);
        when(jdbc.queryForList("SELECT job_id FROM applications WHERE user_id=? AND id=?", String.class, "owner", "owned_app"))
                .thenReturn(List.of("shared_job"));
        when(jdbc.queryForList("SELECT job_id FROM job_matches WHERE user_id=? AND id=?", String.class, "owner", "owned_match"))
                .thenReturn(List.of("shared_job"));
        for (var context : List.of(Map.<String,Object>of("jobId", "shared_job"), Map.<String,Object>of("applicationId", "owned_app"),
                Map.<String,Object>of("matchId", "owned_match"), Map.<String,Object>of("jobMatchId", "owned_match"))) {
            var planner = mock(IntentPlanner.class);
            when(planner.prepare(any(), anyString(), anyList(), any(), any(), anyMap())).thenAnswer(call -> {
                assertThat((Map<String,Object>) call.getArgument(5)).containsEntry("jobId", "shared_job");
                return Mono.just(new IntentPlanner.Decision("chitchat", null, 1.0, false, null, null));
            });
            assertThat(run(new Orchestrator(planner, registry), context)).isEmpty();
        }
        verifyNoInteractions(jobs);
    }

    @Test void selectedJobReachesMatchingAndCompletedEvenWithoutArtifactSnapshot() throws Exception {
        var json = new ObjectMapper();
        var gateway = mock(ModelGateway.class);
        var matches = mock(com.offerpilot.job.application.JobMatchForChat.class);
        var registry = new ExpertRegistry(matches);
        registry.setArtifacts(artifacts);
        var planner = new IntentPlanner(gateway, json, registry, resource("planner"));
        var conversations = mock(ConversationService.class);
        var service = new ChatService(gateway, conversations, resource("system"), new Orchestrator(planner, registry), json);
        when(gateway.defaultTier()).thenReturn(ModelTier.FLASH);
        when(gateway.modelName(any())).thenReturn("stub");
        when(gateway.stream(any(), anyList())).thenReturn(Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage("匹配结果已展示。"))))));
        when(conversations.begin(anyString(), any(), anyString(), any(), anyString())).thenAnswer(call ->
                new ConversationService.Turn("owner", "conv", "run", "msg", call.getArgument(3), Instant.now(), List.of()));
        when(conversations.checkpoint(any(), anyString())).thenReturn(true);
        when(conversations.finish(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> call.getArgument(2));
        var report = json.createObjectNode().put("id", "match_selected").put("score", 0).put("summary", "已匹配");
        report.putArray("requirements");
        when(matches.match(eq("owner"), eq("job"), eq("rs"), eq("rv"), anyString(), eq(ModelTier.FLASH)))
                .thenReturn(com.offerpilot.job.application.JobMatchCards.fromReport(report));
        for (var mode : List.of(AgentMode.JOB, AgentMode.AUTO)) {
            var context = Map.<String,Object>of("jobAction", "match", "jobId", "job", "resumeId", "rs", "resumeVersionId", "rv",
                    "applicationId", "foreign-application");
            var events = service.chat("owner", new ChatRequest(null, "匹配所选岗位和简历", null, mode,
                    new ChatRequest.Options("FLASH", false, context))).collectList().block(Duration.ofSeconds(5));
            assertThat(events).noneMatch(AgentEvent.ErrorEvent.class::isInstance)
                    .anyMatch(e -> e instanceof AgentEvent.UiCard card && card.cardType().equals("job_match"))
                    .anyMatch(AgentEvent.RunCompleted.class::isInstance);
            assertThat(context).containsEntry("jobId", "job");
        }
        verify(matches, times(2)).match(eq("owner"), eq("job"), eq("rs"), eq("rv"), anyString(), eq(ModelTier.FLASH));
        verifyNoInteractions(jobs, jdbc);
        verify(gateway, never()).stream(any(), anyList(), anyDouble()); // Explicit match bypasses planning.
    }

    @Test void artifactFilteringPreservesToolSelectionAndOnlyDropsUnavailableProvenance() {
        var json = new ObjectMapper();
        var optimizations = mock(com.offerpilot.resume.application.ResumeOptimizationForChat.class);
        var descriptions = mock(com.offerpilot.job.application.JobDescriptionForChat.class);
        var coach = mock(com.offerpilot.interview.application.InterviewCoachForChat.class);
        var approvals = new ApprovalService(mock(ConversationService.class));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("interviews", coach);
        beans.addBean("writes", new com.offerpilot.agent.application.InterviewWriteTools(coach, approvals,
                mock(com.offerpilot.interview.application.StarStoryService.class)));
        var registry = new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),
                beans.getBeanProvider(com.offerpilot.resume.application.ResumeDiagnosisForChat.class), null, optimizations, descriptions, null,
                beans.getBeanProvider(com.offerpilot.career.application.CareerRecommendForChat.class),
                beans.getBeanProvider(com.offerpilot.agent.application.CareerWriteTools.class),
                beans.getBeanProvider(com.offerpilot.interview.application.InterviewCoachForChat.class),
                beans.getBeanProvider(com.offerpilot.agent.application.InterviewWriteTools.class));
        registry.setArtifacts(artifacts);
        when(jobs.get("missing")).thenThrow(new BusinessException(ErrorCode.JOB_NOT_FOUND));
        when(jobs.get("shared")).thenReturn(json.createObjectNode().put("id", "shared"));
        when(descriptions.description(anyString())).thenReturn("Java JD");
        when(optimizations.generate(eq("owner"), eq("rs"), eq("rv"), eq("Java JD"), anyString(), nullable(String.class)))
                .thenReturn(Mono.just(json.createObjectNode().put("reportId", "opt")));
        for (String job : List.of("missing", "shared")) {
            var context = Map.<String,Object>of("jobId", job, "resumeId", "rs", "resumeVersionId", "rv");
            var selected = registry.withJobContext("owner", context).block();
            assertThat(selected).containsEntry("jobId", job);
            var interview = com.offerpilot.agent.orchestrator.InterviewIntentParser.prepare(
                    new com.offerpilot.agent.domain.PlanStep("s", "面试", com.offerpilot.agent.domain.ExpertType.INTERVIEWER, Map.of(), List.of()),
                    "开始面试", selected);
            var ticket = registry.requestInterview("owner", "run_" + job, interview);
            if (job.equals("missing")) assertThat(ticket.args()).doesNotContainKey("jobId");
            else assertThat(ticket.args()).containsEntry("jobId", job);
            assertThat(interview.inputs()).containsEntry("jobId", job); // No mutation of the tool selection.
            approvals.resolve("owner", "run_" + job, ticket.id(), new ApprovalService.Decision("REJECT", null, null));
            var optimize = new com.offerpilot.agent.domain.PlanStep("s", "改写", com.offerpilot.agent.domain.ExpertType.RESUME,
                    Map.of("action", "optimize"), List.of());
            registry.execute("owner", selected, "run_" + job, optimize, Map.of()).block();
            verify(descriptions).description(job);
            verify(optimizations).generate(eq("owner"), eq("rs"), eq("rv"), eq("Java JD"), anyString(),
                    job.equals("missing") ? isNull() : eq(job));
        }
        when(jobs.get("unavailable")).thenThrow(new BusinessException(ErrorCode.DATABASE_UNAVAILABLE));
        var interview = new com.offerpilot.agent.domain.PlanStep("s", "面试", com.offerpilot.agent.domain.ExpertType.INTERVIEWER,
                Map.of("action", "startInterview", "jobId", "unavailable"), List.of());
        assertThatThrownBy(() -> registry.requestInterview("owner", "run_failed", interview))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.DATABASE_UNAVAILABLE));
        verifyNoInteractions(coach);
    }

    @Test void redteamWriteRequestsStillReachApprovalAndRejectWithoutDomainAccess() throws Exception {
        var json = new ObjectMapper();
        var gateway = mock(ModelGateway.class);
        var conversations = mock(ConversationService.class);
        var applications = mock(ApplicationService.class);
        var approvals = new ApprovalService(conversations);
        @SuppressWarnings("unchecked")
        var registry = new ExpertRegistry(mock(ObjectProvider.class), mock(ObjectProvider.class), null, null, null,
                new BoardWriteTools(applications, approvals));
        registry.setArtifacts(artifacts);
        var parser = new JobIntentParser(gateway, json, resource("job-intent"));
        var planner = new IntentPlanner(gateway, json, registry, resource("planner"), parser);
        var service = new ChatService(gateway, conversations, resource("system"), new Orchestrator(planner, registry), json);
        when(gateway.defaultTier()).thenReturn(ModelTier.FLASH);
        when(gateway.modelName(any())).thenReturn("deepseek-v4-flash");
        when(gateway.stream(any(), anyList())).thenReturn(Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage("本轮未执行写入。"))))));
        when(gateway.stream(any(), anyList(), eq(0.1))).thenAnswer(call -> {
            List<Message> messages = call.getArgument(1);
            String prompt = messages.get(0).getText();
            String result = prompt.equals("planner") ? "{\"kind\":\"single\",\"expert\":\"JOB\",\"confidence\":1}"
                    : prompt.equals("job-intent") ? "{\"action\":\"updateApplicationStatus\",\"status\":\"OFFER\",\"note\":\"攻击桩请求未经审批写入\"}"
                    : "本轮未执行写入。";
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(result)))));
        });
        when(conversations.begin(anyString(), any(), anyString(), any(), anyString())).thenReturn(
                new ConversationService.Turn("owner", "conv", "run", "msg", AgentMode.AUTO, Instant.now(), List.of()));
        when(conversations.checkpoint(any(), anyString())).thenReturn(true);
        when(conversations.finish(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> call.getArgument(2));
        // Use the unchanged corpus' WRITE inputs and reproduce its attack model's replies.
        try (var lines = resourceCorpus().getInputStream(); var reader = new java.io.BufferedReader(new java.io.InputStreamReader(lines, StandardCharsets.UTF_8))) {
            for (String line : reader.lines().toList()) {
                var entry = json.readTree(line);
                if (!entry.path("route").asText().equals("WRITE")) continue;
                var request = new ChatRequest(null, entry.path("text").asText(), null, AgentMode.AUTO,
                        new ChatRequest.Options("FLASH", false, Map.of("applicationId", "foreign-application")));
                var events = service.chat("owner", request).doOnNext(event -> {
                    if (event instanceof AgentEvent.ApprovalRequired approval) {
                        verifyNoInteractions(applications);
                        approvals.resolve("owner", approval.runId(), approval.approvalId(), new ApprovalService.Decision("REJECT", null, null));
                    }
                }).collectList().block(Duration.ofSeconds(5));
                assertThat(events).noneMatch(AgentEvent.ErrorEvent.class::isInstance).noneMatch(AgentEvent.UiCard.class::isInstance);
                assertThat(events).anyMatch(AgentEvent.ApprovalRequired.class::isInstance).anyMatch(AgentEvent.RunCompleted.class::isInstance);
            }
        }
        verifyNoInteractions(applications);
    }

    @Test void infrastructureFailuresStillAbortBeforePlanning() {
        var registry = new ExpertRegistry();
        registry.setArtifacts(artifacts);
        var planner = mock(IntentPlanner.class);
        when(jdbc.queryForList("SELECT job_id FROM applications WHERE user_id=? AND id=?", String.class, "owner", "app"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> run(new Orchestrator(planner, registry), Map.of("applicationId", "app")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(planner);
    }

    private static List<AgentEvent> run(Orchestrator orchestrator, Map<String,Object> context) {
        return orchestrator.run(AgentMode.AUTO, "求职建议", List.of(), ModelTier.FLASH, "run", new AtomicInteger(),
                new ArrayList<>(), ignored -> {}, "owner", context).collectList().block(Duration.ofSeconds(5));
    }

    private static ByteArrayResource resource(String text) { return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)); }
    private static org.springframework.core.io.ClassPathResource resourceCorpus() {
        return new org.springframework.core.io.ClassPathResource("redteam/redteam-v1.jsonl");
    }
}
