package com.offerpilot.agent.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.domain.ExpertType;

import java.time.Instant;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AgentEvent.RunStarted.class, name = "run.started"),
        @JsonSubTypes.Type(value = AgentEvent.PlanCreated.class, name = "plan.created"),
        @JsonSubTypes.Type(value = AgentEvent.StepUpdated.class, name = "step.updated"),
        @JsonSubTypes.Type(value = AgentEvent.ReasoningDelta.class, name = "reasoning.delta"),
        @JsonSubTypes.Type(value = AgentEvent.TextDelta.class, name = "text.delta"),
        @JsonSubTypes.Type(value = AgentEvent.ToolCall.class, name = "tool.call"),
        @JsonSubTypes.Type(value = AgentEvent.ToolResult.class, name = "tool.result"),
        @JsonSubTypes.Type(value = AgentEvent.UiCard.class, name = "ui.card"),
        @JsonSubTypes.Type(value = AgentEvent.ApprovalRequired.class, name = "approval.required"),
        @JsonSubTypes.Type(value = AgentEvent.ApprovalResolved.class, name = "approval.resolved"),
        @JsonSubTypes.Type(value = AgentEvent.Citation.class, name = "citation"),
        @JsonSubTypes.Type(value = AgentEvent.MemoryUsed.class, name = "memory.used"),
        @JsonSubTypes.Type(value = AgentEvent.Usage.class, name = "usage"),
        @JsonSubTypes.Type(value = AgentEvent.ErrorEvent.class, name = "error"),
        @JsonSubTypes.Type(value = AgentEvent.RunCompleted.class, name = "run.completed")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface AgentEvent permits
        AgentEvent.RunStarted,
        AgentEvent.PlanCreated,
        AgentEvent.StepUpdated,
        AgentEvent.ReasoningDelta,
        AgentEvent.TextDelta,
        AgentEvent.ToolCall,
        AgentEvent.ToolResult,
        AgentEvent.UiCard,
        AgentEvent.ApprovalRequired,
        AgentEvent.ApprovalResolved,
        AgentEvent.Citation,
        AgentEvent.MemoryUsed,
        AgentEvent.Usage,
        AgentEvent.ErrorEvent,
        AgentEvent.RunCompleted {

    String type();

    int id();

    String runId();

    Instant ts();

    record RunStarted(
            String type,
            int id,
            String runId,
            Instant ts,
            String conversationId,
            String messageId,
            String model,
            AgentMode mode
    ) implements AgentEvent {
        public RunStarted {
            type = "run.started";
        }
    }

    record PlanCreated(
            String type,
            int id,
            String runId,
            Instant ts,
            String goal,
            List<PlanStepView> steps
    ) implements AgentEvent {
        public PlanCreated {
            type = "plan.created";
        }
    }

    record StepUpdated(
            String type,
            int id,
            String runId,
            Instant ts,
            String stepId,
            StepStatus status,
            String summary,
            Integer durationMs
    ) implements AgentEvent {
        public StepUpdated {
            type = "step.updated";
        }
    }

    record ReasoningDelta(String type, int id, String runId, Instant ts, String stepId, String text) implements AgentEvent {
        public ReasoningDelta {
            type = "reasoning.delta";
        }
    }

    record TextDelta(String type, int id, String runId, Instant ts, String stepId, String text) implements AgentEvent {
        public TextDelta {
            type = "text.delta";
        }
    }

    record ToolCall(
            String type,
            int id,
            String runId,
            Instant ts,
            String stepId,
            String toolCallId,
            String name,
            Object args,
            String source,
            ToolAccess access
    ) implements AgentEvent {
        public ToolCall {
            type = "tool.call";
        }
    }

    record ToolResult(
            String type,
            int id,
            String runId,
            Instant ts,
            String stepId,
            String toolCallId,
            String name,
            boolean ok,
            String resultPreview,
            int durationMs,
            boolean truncated
    ) implements AgentEvent {
        public ToolResult {
            type = "tool.result";
        }
    }

    record UiCard(
            String type,
            int id,
            String runId,
            Instant ts,
            String stepId,
            String cardId,
            String cardType,
            Object payload
    ) implements AgentEvent {
        public UiCard {
            type = "ui.card";
        }
    }

    record ApprovalRequired(
            String type,
            int id,
            String runId,
            Instant ts,
            String stepId,
            String approvalId,
            String toolCallId,
            String name,
            Object args,
            String reason,
            Instant expiresAt,
            List<String> editableFields
    ) implements AgentEvent {
        public ApprovalRequired {
            type = "approval.required";
        }
    }

    record ApprovalResolved(
            String type,
            int id,
            String runId,
            Instant ts,
            String approvalId,
            String decision
    ) implements AgentEvent {
        public ApprovalResolved {
            type = "approval.resolved";
        }
    }

    record Citation(
            String type,
            int id,
            String runId,
            Instant ts,
            String stepId,
            List<CitationSource> sources
    ) implements AgentEvent {
        public Citation {
            type = "citation";
        }
    }

    record MemoryUsed(
            String type,
            int id,
            String runId,
            Instant ts,
            List<MemoryFact> facts
    ) implements AgentEvent {
        public MemoryUsed {
            type = "memory.used";
        }
    }

    record Usage(
            String type,
            int id,
            String runId,
            Instant ts,
            String model,
            int promptTokens,
            int completionTokens,
            double costEstimate,
            boolean fallbackUsed
    ) implements AgentEvent {
        public Usage {
            type = "usage";
        }
    }

    record ErrorEvent(
            String type,
            int id,
            String runId,
            Instant ts,
            int code,
            String message,
            boolean recoverable
    ) implements AgentEvent {
        public ErrorEvent {
            type = "error";
        }
    }

    record RunCompleted(
            String type,
            int id,
            String runId,
            Instant ts,
            String messageId,
            String status,
            int durationMs
    ) implements AgentEvent {
        public RunCompleted {
            type = "run.completed";
        }
    }

    enum StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    enum ToolAccess { READ, WRITE, EXTERNAL_SIDE_EFFECT }

    record PlanStepView(String id, String title, ExpertType agent, StepStatus status, List<String> dependsOn) {
    }

    record CitationSource(
            String docId,
            String title,
            String snippet,
            double score,
            String url,
            String collection,
            int index
    ) {
    }

    record MemoryFact(String id, String fact) {
    }
}
