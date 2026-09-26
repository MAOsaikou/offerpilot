package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.job.application.JobMatchCards;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ByteArrayResource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MatchJobExecutionTest {
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final ObjectMapper json = new ObjectMapper();
    private final List<Message> reply = new ArrayList<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Test
    void contextTripleEmitsJobMatchAndMissingIdsStayPlaceholder() throws Exception {
        ExpertRegistry experts = new ExpertRegistry((owner, jobId, resumeId, versionId, requestId) -> {
            calls.add(jobId);
            ObjectNode report = json.createObjectNode().put("id", "match_01abc").put("score", 80).put("summary", "证据可用");
            report.set("requirements", json.createArrayNode());
            report.set("resumeSnapshot", json.createObjectNode().put("summary", "快照"));
            return JobMatchCards.fromReport(report);
        });
        Orchestrator orchestrator = new Orchestrator(new IntentPlanner(gateway, json, experts, new ByteArrayResource("{experts}".getBytes())), experts);
        Map<String, Object> context = Map.of("jobId", "job_1", "resumeId", "res_1", "resumeVersionId", "ver_1");

        var matched = orchestrator.run(AgentMode.JOB, "对照这份简历", List.of(), ModelTier.CHEAP, "run_1", new AtomicInteger(),
                reply, usage -> {}, "owner", context).collectList().block(Duration.ofSeconds(5));

        assertThat(calls).containsExactly("job_1");
        assertThat(matched).extracting(AgentEvent::type).containsExactly(
                "plan.created", "step.updated", "tool.call", "tool.result", "ui.card", "step.updated");
        assertThat(((AgentEvent.PlanCreated) matched.get(0)).steps().get(0).title()).doesNotContain("占位");
        var call = (AgentEvent.ToolCall) matched.get(2);
        assertThat(call.name()).isEqualTo("matchJobToResume");
        assertThat(call.access()).isEqualTo(AgentEvent.ToolAccess.READ);
        var card = (AgentEvent.UiCard) matched.get(4);
        assertThat(card.cardId()).isEqualTo("match_01abc");
        assertThat(card.cardType()).isEqualTo("job_match");
        assertThat(card.payload().toString()).doesNotContain("快照");
        assertThat(reply.get(0).getText()).contains("结果已作为卡片展示").doesNotContain("未执行真实业务操作");

        reply.clear();
        var placeholder = orchestrator.run(AgentMode.JOB, "没有标识", List.of(), ModelTier.CHEAP, "run_2", new AtomicInteger(),
                reply, usage -> {}, "owner", Map.of("resumeId", "res_1")).collectList().block(Duration.ofSeconds(5));
        assertThat(calls).containsExactly("job_1");
        assertThat(placeholder).extracting(AgentEvent::type).doesNotContain("ui.card");
        assertThat(((AgentEvent.ToolCall) placeholder.get(2)).name()).isEqualTo("placeholder.JOB");
        assertThat(reply.get(0).getText()).contains("未执行真实业务操作");
    }

    @Test
    void rejectedIdsFailTheStepWithoutACardOrSubstitutedTarget() throws Exception {
        ExpertRegistry experts = new ExpertRegistry((owner, jobId, resumeId, versionId, requestId) -> {
            calls.add(jobId);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "简历版本不可用");
        });
        Orchestrator orchestrator = new Orchestrator(new IntentPlanner(gateway, json, experts, new ByteArrayResource("{experts}".getBytes())), experts);
        var events = orchestrator.run(AgentMode.JOB, "匹配", List.of(), ModelTier.CHEAP, "run_1", new AtomicInteger(),
                reply, usage -> {}, "owner", Map.of("jobId", "job_missing", "resumeId", "res_1", "resumeVersionId", "ver_1"))
                .collectList().block(Duration.ofSeconds(5));
        assertThat(calls).containsExactly("job_missing");
        assertThat(events).extracting(AgentEvent::type).doesNotContain("ui.card");
        assertThat(events).filteredOn(AgentEvent.ToolResult.class::isInstance).singleElement()
                .isInstanceOfSatisfying(AgentEvent.ToolResult.class, result -> {
                    assertThat(result.ok()).isFalse();
                    assertThat(result.name()).isEqualTo("matchJobToResume");
                    assertThat(result.resultPreview()).isEqualTo("简历版本不可用");
                });
        assertThat(events.toString()).doesNotContain("secret");
        assertThat(reply.get(0).getText()).contains("不要声称匹配成功");
    }
}
