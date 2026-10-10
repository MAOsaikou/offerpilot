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
        for (String key : List.of("applicationId", "matchId", "jobMatchId", "jobId")) {
            for (String id : List.of("missing", "foreign")) {
                if (key.equals("jobId")) when(jobs.get(id)).thenThrow(new BusinessException(ErrorCode.JOB_NOT_FOUND));
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
        when(jobs.get("shared_job")).thenReturn(new ObjectMapper().createObjectNode().put("id", "shared_job"));
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
        verify(jobs).get("shared_job");
    }

    @Test void invalidJobSelectionKeepsExplicitMatchValidationWithoutExistenceError() throws Exception {
        var gateway = mock(ModelGateway.class);
        var registry = new ExpertRegistry();
        registry.setArtifacts(artifacts);
        when(jobs.get("missing")).thenThrow(new BusinessException(ErrorCode.JOB_NOT_FOUND));
        var planner = new IntentPlanner(gateway, new ObjectMapper(), registry, resource("planner"));
        assertThatThrownBy(() -> run(new Orchestrator(planner, registry), Map.of("jobAction", "match", "jobId", "missing",
                "resumeId", "resume_selected", "resumeVersionId", "version_selected")))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("请先选择岗位");
        verifyNoInteractions(gateway);
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
        when(jobs.get("job")).thenThrow(new BusinessException(ErrorCode.DATABASE_UNAVAILABLE));
        assertThatThrownBy(() -> run(new Orchestrator(planner, registry), Map.of("jobId", "job")))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.DATABASE_UNAVAILABLE));
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
