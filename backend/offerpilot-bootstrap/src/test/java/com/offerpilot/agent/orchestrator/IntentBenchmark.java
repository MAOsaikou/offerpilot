package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.springframework.ai.chat.messages.*;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.*;

/** Shared corpus and production path for deterministic replay and opt-in live evaluation. */
final class IntentBenchmark {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String FIXTURE = "eval/intent/intent-cases.json";

    static JsonNode load() throws Exception {
        try (var input = new ClassPathResource(FIXTURE).getInputStream()) {
            return JSON.readTree(input);
        }
    }

    static IntentPlanner planner(ModelGateway gateway) throws Exception {
        return new IntentPlanner(gateway, JSON, new ExpertRegistry(), new ClassPathResource("prompts/agent/plan.st"),
                new JobIntentParser(gateway, JSON, new ClassPathResource("prompts/agent/job-intent.st")),
                new ResumeIntentParser(gateway, JSON, new ClassPathResource("prompts/agent/resume-intent.st")));
    }

    static IntentPlanner.Decision prepare(IntentPlanner planner, JsonNode sample) {
        List<Message> history = new ArrayList<>();
        for (var message : sample.path("history")) {
            history.add(switch (message.path("role").asText()) {
                case "user" -> new UserMessage(message.path("content").asText());
                case "assistant" -> new AssistantMessage(message.path("content").asText());
                default -> throw new IllegalArgumentException("Unsupported fixture history role");
            });
        }
        Map<String, Object> context = sample.has("context")
                ? JSON.convertValue(sample.get("context"), new com.fasterxml.jackson.core.type.TypeReference<>() {}) : Map.of();
        return planner.prepare(AgentMode.valueOf(sample.path("mode").asText("AUTO")), sample.path("message").asText(),
                history, ModelTier.CHEAP, ignored -> {}, context).block(Duration.ofMinutes(10));
    }

    static boolean correct(JsonNode sample, IntentPlanner.Decision decision) {
        return decision != null && Objects.equals(sample.path("expected").path("kind").asText(), decision.kind())
                && Objects.equals(expert(sample.path("expected").path("expert")),
                    decision.expert() == null ? null : decision.expert().name());
    }

    static String expert(JsonNode value) { return value.isNull() ? null : value.asText(); }
}
