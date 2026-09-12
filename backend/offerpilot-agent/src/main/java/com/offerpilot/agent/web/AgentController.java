package com.offerpilot.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final ObjectMapper objectMapper;
    private final ModelGateway modelGateway;

    public AgentController(ObjectMapper objectMapper, ModelGateway modelGateway) {
        this.objectMapper = objectMapper;
        this.modelGateway = modelGateway;
    }

    /**
     * M0 Hello World：按 SSE 协议回显用户输入，后续替换为 Orchestrator。
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(10 * 60_000L);
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? IdGenerator.next("conv")
                : request.conversationId();
        String runId = IdGenerator.next("run");
        String messageId = IdGenerator.next("msg");
        AgentMode mode = request.mode() == null ? AgentMode.AUTO : request.mode();
        ModelTier tier = request.options() == null
                ? modelGateway.defaultTier()
                : ModelTier.from(request.options().modelTier());
        String model = modelGateway.modelName(tier);
        Instant startedAt = Instant.now();
        AtomicInteger seq = new AtomicInteger(1);

        Thread.startVirtualThread(() -> {
            try {
                emit(emitter, seq.getAndIncrement(), "run.started", new AgentEvent.RunStarted(
                        "run.started", 0, runId, Instant.now(), conversationId, messageId, model, mode
                ));
                String echo = "OfferPilot 已收到：「" + request.content() + "」。编排内核将在 M1/M2 接入。";
                for (String chunk : echo.split("(?<=。|」)")) {
                    if (chunk.isBlank()) {
                        continue;
                    }
                    emit(emitter, seq.getAndIncrement(), "text.delta", new AgentEvent.TextDelta(
                            "text.delta", 0, runId, Instant.now(), null, chunk
                    ));
                    Thread.sleep(40);
                }
                emit(emitter, seq.getAndIncrement(), "run.completed", new AgentEvent.RunCompleted(
                        "run.completed",
                        0,
                        runId,
                        Instant.now(),
                        messageId,
                        "COMPLETED",
                        (int) (Instant.now().toEpochMilli() - startedAt.toEpochMilli())
                ));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    private void emit(SseEmitter emitter, int id, String eventName, AgentEvent event) throws IOException {
        AgentEvent withId = withId(event, id);
        emitter.send(SseEmitter.event()
                .id(String.valueOf(id))
                .name(eventName)
                .data(objectMapper.writeValueAsString(withId), MediaType.APPLICATION_JSON));
    }

    private static AgentEvent withId(AgentEvent event, int id) {
        return switch (event) {
            case AgentEvent.RunStarted e -> new AgentEvent.RunStarted(
                    e.type(), id, e.runId(), e.ts(), e.conversationId(), e.messageId(), e.model(), e.mode()
            );
            case AgentEvent.TextDelta e -> new AgentEvent.TextDelta(e.type(), id, e.runId(), e.ts(), e.stepId(), e.text());
            case AgentEvent.RunCompleted e -> new AgentEvent.RunCompleted(
                    e.type(), id, e.runId(), e.ts(), e.messageId(), e.status(), e.durationMs()
            );
            case AgentEvent.PlanCreated e -> e;
            case AgentEvent.StepUpdated e -> e;
            case AgentEvent.ReasoningDelta e -> e;
            case AgentEvent.ToolCall e -> e;
            case AgentEvent.ToolResult e -> e;
            case AgentEvent.UiCard e -> e;
            case AgentEvent.ApprovalRequired e -> e;
            case AgentEvent.ApprovalResolved e -> e;
            case AgentEvent.Citation e -> e;
            case AgentEvent.MemoryUsed e -> e;
            case AgentEvent.Usage e -> e;
            case AgentEvent.ErrorEvent e -> e;
        };
    }
}
