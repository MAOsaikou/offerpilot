package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BoardIntentParserTest {
    @Test void recognizesStatusAndNoteUsingCallerIdsAndClarifiesMissingSelection() throws Exception {
        var gateway = mock(ModelGateway.class);
        var parser = new JobIntentParser(gateway, new ObjectMapper(), new ByteArrayResource("board actions".getBytes()));
        var step = new PlanStep("s1", "看板修改", ExpertType.JOB, Map.of(), List.of());
        for (String action : List.of("createApplication", "updateApplicationStatus")) {
            String key = action.equals("createApplication") ? "jobId" : "applicationId";
            when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"action\":\"" + action + "\",\"status\":\"INTERVIEW\",\"note\":\"周五面试\"}"))))));
            var result = parser.prepare(step, "把所选投递改成面试中", List.of(), ModelTier.FLASH, u -> {}, Map.of(key, "selected1")).block();
            assertThat(result.inputs()).containsEntry("action", action).containsEntry(key, "selected1")
                    .containsEntry("status", "INTERVIEW").containsEntry("note", "周五面试");
            assertThat(parser.prepare(step, "改成面试中", List.of(), ModelTier.FLASH, u -> {}, Map.of()).block().inputs())
                    .containsEntry("action", "clarify");
        }
    }
    @Test void explicitSelectedJobAddRoutesWithoutPlanningModelForEitherTier() throws Exception {
        var gateway = mock(ModelGateway.class);
        var parser = new JobIntentParser(gateway, new ObjectMapper(), new ByteArrayResource("board".getBytes()));
        var experts = new com.offerpilot.agent.experts.ExpertRegistry();
        var planner = new IntentPlanner(gateway, new ObjectMapper(), experts, new ByteArrayResource("planner".getBytes()), parser, null);
        for (var tier : List.of(ModelTier.FLASH, ModelTier.PRO)) {
            for (var mode : List.of(com.offerpilot.agent.domain.AgentMode.AUTO, com.offerpilot.agent.domain.AgentMode.JOB)) {
                var decision = planner.prepare(mode, "把这个岗位加入求职看板", List.of(), tier, u -> {}, Map.of("jobId", "selected-job")).block();
                assertThat(decision.needsClarification()).isFalse();
                assertThat(decision.plan().steps().get(0).inputs()).containsExactlyInAnyOrderEntriesOf(Map.of("action", "createApplication", "jobId", "selected-job"));
                assertThat(planner.prepare(mode, "把这个岗位加入求职看板", List.of(), tier, u -> {}, Map.of()).block().needsClarification()).isTrue();
            }
        }
        verifyNoInteractions(gateway);
        assertThat(BoardIntent.matches("不要把这个岗位加入求职看板", Map.of("jobId", "selected-job"))).isFalse();
        assertThat(BoardIntent.matches("把这个岗位加入求职看板后搜索Java", Map.of("jobId", "selected-job"))).isFalse();
        assertThat(BoardIntent.matches("帮我匹配这个岗位", Map.of("jobId", "selected-job"))).isFalse();
    }
    @Test void autoPlanningReceivesTheSameBoundedSelectionsAsJobActionParsing() throws Exception {
        var gateway = mock(ModelGateway.class);
        when(gateway.stream(any(), anyList(), eq(0.1))).thenAnswer(invocation -> {
            List<org.springframework.ai.chat.messages.Message> messages = invocation.getArgument(1);
            String request = messages.get(messages.size() - 1).getText();
            assertThat(request).contains("jobId=true", "resumeId=true", "resumeVersionId=false", "applicationId=false")
                    .doesNotContain("private-selected-id", "x".repeat(81));
            String output = messages.get(0).getText().equals("planner")
                    ? "{\"kind\":\"single\",\"expert\":\"JOB\",\"confidence\":1,\"needsClarification\":false}"
                    : "{\"action\":\"createApplication\"}";
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
        });
        var parser = new JobIntentParser(gateway, new ObjectMapper(), new ByteArrayResource("job actions".getBytes()));
        var planner = new IntentPlanner(gateway, new ObjectMapper(), new com.offerpilot.agent.experts.ExpertRegistry(),
                new ByteArrayResource("planner".getBytes()), parser, null);
        for (var tier : List.of(ModelTier.FLASH, ModelTier.PRO)) {
            var decision = planner.prepare(com.offerpilot.agent.domain.AgentMode.AUTO,
                    "这个职位我打算申请，请帮我建一个求职跟踪记录，先设为待投递。", List.of(), tier, u -> {},
                    Map.of("jobId", "private-selected-id", "resumeId", "resume-selected", "resumeVersionId", "x".repeat(81), "applicationId", List.of("invalid"))).block();
            assertThat(decision.needsClarification()).isFalse();
            assertThat(decision.plan().steps().get(0).inputs()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("action", "createApplication", "jobId", "private-selected-id"));
        }
    }
    @Test void absentBlankAndNonStringResourcesAreNeverAdvertisedAsSelected() {
        assertThat(SelectedResources.describe(Map.of())).contains("jobId=false", "resumeId=false", "resumeVersionId=false", "applicationId=false");
        assertThat(SelectedResources.describe(Map.of("jobId", "  ", "resumeId", 12, "resumeVersionId", "x".repeat(81))))
                .contains("jobId=false", "resumeId=false", "resumeVersionId=false");
    }

}
