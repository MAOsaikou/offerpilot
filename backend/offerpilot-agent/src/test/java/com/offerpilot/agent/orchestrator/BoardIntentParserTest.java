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
            var result = parser.prepare(step, "把所选投递改成面试中", List.of(), ModelTier.CHEAP, u -> {}, Map.of(key, "selected1")).block();
            assertThat(result.inputs()).containsEntry("action", action).containsEntry(key, "selected1")
                    .containsEntry("status", "INTERVIEW").containsEntry("note", "周五面试");
            assertThat(parser.prepare(step, "改成面试中", List.of(), ModelTier.CHEAP, u -> {}, Map.of()).block().inputs())
                    .containsEntry("action", "clarify");
        }
    }
}
