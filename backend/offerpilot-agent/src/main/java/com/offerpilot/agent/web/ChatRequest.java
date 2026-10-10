package com.offerpilot.agent.web;

import com.offerpilot.agent.domain.AgentMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record ChatRequest(
        String conversationId,
        @NotBlank @Size(min = 1, max = 4000) String content,
        List<Attachment> attachments,
        AgentMode mode,
        Options options
) {
    public record Attachment(String fileId, String kind) {
    }

    public record Options(String modelTier, Boolean enableReasoning, Map<String, Object> context) {
        public Options {
            context = new java.util.LinkedHashMap<>(context == null ? Map.of() : context);
        }

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void legacyInterviewOption(String name, Object value) {
            if (java.util.Set.of("interviewAction", "interviewSessionId").contains(name))
                context.putIfAbsent(name, value);
        }
    }
}
