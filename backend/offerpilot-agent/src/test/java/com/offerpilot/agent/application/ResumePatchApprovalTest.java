package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.*;
import com.offerpilot.agent.orchestrator.*;
import com.offerpilot.career.application.*;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.llm.*;
import com.offerpilot.resume.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumePatchApprovalTest {
    final ObjectMapper json = new ObjectMapper();
    final ResumePatchService patches = mock(ResumePatchService.class);
    final ConversationService conversations = mock(ConversationService.class);
    final ApprovalService approvals = spy(new ApprovalService(conversations));
    final ResumePatchWriteTools tools = new ResumePatchWriteTools(patches, approvals, json);
    final ModelGateway gateway = mock(ModelGateway.class);

    Map<String, Object> args() {
        return Map.of("resumeId", "rs1", "resumeVersionId", "rv1", "batchId", "pb1",
                "accepted", List.of("p1"), "edited", Map.of("p2", Map.of("value", "用户核对后的内容")), "confirmed", List.of("p2"));
    }
    PlanStep write() {
        var inputs = new LinkedHashMap<String, Object>(args()); inputs.put("action", "applyPatches");
        return new PlanStep("s1", "采纳简历补丁", ExpertType.RESUME, inputs, List.of());
    }
    ExpertRegistry registry() {
        var beans = new StaticListableBeanFactory(); beans.addBean("patchWrites", tools);
        beans.addBean("diagnoses", mock(ResumeDiagnosisForChat.class));
        return new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),
                beans.getBeanProvider(ResumeDiagnosisForChat.class), null, mock(ResumeOptimizationForChat.class), null, null,
                beans.getBeanProvider(CareerRecommendForChat.class), beans.getBeanProvider(CareerWriteTools.class),
                beans.getBeanProvider(com.offerpilot.interview.application.InterviewCoachForChat.class), beans.getBeanProvider(InterviewWriteTools.class),
                beans.getBeanProvider(CoachPlanForChat.class), beans.getBeanProvider(CoachWriteTools.class), beans.getBeanProvider(ResumePatchWriteTools.class));
    }
    IntentPlanner planner(ExpertRegistry registry) throws Exception {
        var prompt = new ByteArrayResource("{experts}".getBytes());
        return new IntentPlanner(gateway, json, registry, prompt, null, new ResumeIntentParser(gateway, json, prompt));
    }
    @ParameterizedTest @ValueSource(strings = {"APPROVE", "REJECT", "TIMEOUT"})
    void writeIsGatedAndOnlyApprovalAppliesTrustedSelections(String decision) throws Exception {
        if (decision.equals("TIMEOUT")) doAnswer(call -> approvals.request(call.getArgument(0), call.getArgument(1),
                call.getArgument(2), call.getArgument(3), Duration.ofMillis(150)))
                .when(approvals).request(anyString(), anyString(), eq("applyPatches"), anyMap());
        var version = new ResumeService.Version("rv2", 2, "rv1", "OPTIMIZED", json.createObjectNode(), "", null, Instant.now());
        when(patches.apply(eq("alice"), eq("rs1"), eq("rv1"), any(JsonNode.class)))
                .thenReturn(new ResumePatchService.Applied("pb1", version));
        var registry = registry();
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var reply = new ArrayList<org.springframework.ai.chat.messages.Message>();
        var required = new CompletableFuture<AgentEvent.ApprovalRequired>();
        var context = new LinkedHashMap<String, Object>(args());
        context.put("resumeAction", "applyPatches"); context.put("owner", "attacker");
        var completed = new Orchestrator(planner(registry), registry).run(AgentMode.AUTO, "请采纳所选建议", List.of(), ModelTier.CHEAP,
                "run", new AtomicInteger(), reply, usage -> {}, "alice", context)
                .doOnNext(event -> { events.add(event); if (event instanceof AgentEvent.ApprovalRequired a) required.complete(a); })
                .then().toFuture();
        var ticket = required.get(3, TimeUnit.SECONDS);
        verifyNoInteractions(patches, gateway);
        assertThat(ticket.name()).isEqualTo("applyPatches");
        assertThat(ticket.args()).isEqualTo(args());
        assertThat(ticket.editableFields()).isEmpty();
        assertThat(((AgentEvent.ToolCall) events.get(2)).access()).isEqualTo(AgentEvent.ToolAccess.WRITE);
        if (!decision.equals("TIMEOUT")) {
            assertThatThrownBy(() -> approvals.resolve("attacker", "run", ticket.approvalId(), new ApprovalService.Decision("APPROVE", null, null)))
                    .hasMessageContaining("不存在");
            assertThatThrownBy(() -> approvals.resolve("alice", "run", ticket.approvalId(), new ApprovalService.Decision("APPROVE", Map.of("accepted", List.of("p3")), null)))
                    .hasMessageContaining("不允许编辑");
            approvals.resolve("alice", "run", ticket.approvalId(), new ApprovalService.Decision(decision, Map.of(), null));
        }
        completed.get(3, TimeUnit.SECONDS);
        assertThat(events).extracting(AgentEvent::type).containsExactly("plan.created", "step.updated", "tool.call", "approval.required", "approval.resolved", "tool.result", "step.updated");
        assertThat(((AgentEvent.ApprovalResolved) events.get(4)).decision()).isEqualTo(decision);
        assertThat(((AgentEvent.ToolResult) events.get(5)).ok()).isEqualTo(decision.equals("APPROVE"));
        if (decision.equals("APPROVE")) {
            verify(patches).apply("alice", "rs1", "rv1", json.valueToTree(Map.of("batchId", "pb1", "accepted", List.of("p1"),
                    "edited", Map.of("p2", Map.of("value", "用户核对后的内容")), "confirmed", List.of("p2"))));
            assertThat(((AgentEvent.ToolResult) events.get(5)).resultPreview()).contains("rv2", "V2");
            assertThat(reply.get(0).getText()).contains("只有工具明确返回批准后采纳成功").doesNotContain("未成功");
        } else verifyNoInteractions(patches);
        verifyNoInteractions(gateway);
    }
    @Test void validationUsesTheExistingDecisionContractAndRequestCannotWrite() {
        var invalid = new ArrayList<Map<String, Object>>();
        var missing = new LinkedHashMap<>(args()); missing.remove("batchId"); invalid.add(missing);
        for (String key : List.of("resumeId", "resumeVersionId", "batchId")) {
            for (String value : List.of("", "../bad", "x".repeat(101))) {
                var bad = new LinkedHashMap<>(args()); bad.put(key, value); invalid.add(bad);
            }
        }
        for (var extra : List.of(Map.of("accepted", List.of("bad/id")), Map.of("accepted", List.of("p1", "p1")),
                Map.of("accepted", List.of(), "edited", Map.of()), Map.of("confirmed", List.of("p3")),
                Map.of("edited", Map.of("p1", Map.of("value", "overlap"))), Map.of("edited", Map.of("p2", "wrong shape")),
                Map.of("accepted", Collections.nCopies(51, "p1")), Map.of("owner", "attacker"))) {
            var bad = new LinkedHashMap<>(args()); bad.putAll(extra); invalid.add(bad);
        }
        for (var bad : invalid) assertThatThrownBy(() -> approvals.request("alice", "run", "applyPatches", bad))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        var editedOnly = new LinkedHashMap<>(args()); editedOnly.put("accepted", List.of());
        ResumePatchWriteTools.validate(editedOnly);
        var badInputs = new LinkedHashMap<String, Object>(); badInputs.put("action", "applyPatches");
        assertThatThrownBy(() -> tools.request("alice", "run", new PlanStep("s1", "采纳", ExpertType.RESUME, badInputs, List.of())))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        verifyNoInteractions(patches);
    }
    @Test void registryRefusesUngatedWritesAndNeverOptimizesOrDiagnosesApply() {
        var registry = registry();
        assertThat(registry.writingPatches(write())).isTrue();
        assertThat(registry.optimizing(write(), args())).isFalse();
        assertThat(registry.diagnosing(write(), args())).isFalse();
        assertThatThrownBy(() -> registry.execute("alice", args(), "run", write(), Map.of()).block()).hasMessageContaining("必须先审批");
        verifyNoInteractions(patches);
    }
    @ParameterizedTest @ValueSource(strings = {"AUTO", "RESUME"})
    void missingContextClarifiesWithoutModelInventingSelections(String mode) throws Exception {
        var context = new LinkedHashMap<>(args()); context.remove("batchId"); context.put("resumeAction", "applyPatches");
        var decision = planner(registry()).prepare(AgentMode.valueOf(mode), "请采纳 pb_model p_model", List.of(), ModelTier.CHEAP,
                usage -> {}, context).block();
        assertThat(decision.needsClarification()).isTrue();
        assertThat(decision.plan()).isNull();
        assertThat(decision.clarifyingQuestion()).contains("卡片");
        verifyNoInteractions(gateway, patches);
    }
    @Test void cancelledWaitNeverAppliesAndApprovedServiceFailureCannotClaimSuccess() {
        var ticket = tools.request("alice", "run", write());
        ticket.await().subscribe().dispose();
        assertThatThrownBy(() -> approvals.resolve("alice", "run", ticket.id(), new ApprovalService.Decision("APPROVE", null, null)))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        verifyNoInteractions(patches);
        when(patches.apply(anyString(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("apply failed"));
        assertThatThrownBy(() -> tools.execute("alice", "run", write(), new ApprovalService.Decision("APPROVE", args(), null)).block())
                .hasMessageContaining("apply failed");
    }
    @Test void ticketPinsMutableCallerSelection() {
        var selected = new ArrayList<>(List.of("p1"));
        var inputs = new LinkedHashMap<>(write().inputs()); inputs.put("accepted", selected);
        var ticket = tools.request("alice", "run", new PlanStep("s1", "采纳", ExpertType.RESUME, inputs, List.of()));
        selected.clear();
        assertThat(ticket.args().get("accepted")).isEqualTo(List.of("p1"));
        verifyNoInteractions(patches);
    }
}
