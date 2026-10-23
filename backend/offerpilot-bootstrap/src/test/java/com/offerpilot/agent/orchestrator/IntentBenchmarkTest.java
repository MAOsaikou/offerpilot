package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.agent.domain.Plan;
import com.offerpilot.llm.ModelGateway;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class IntentBenchmarkTest {
    @TestFactory
    Stream<DynamicTest> replaysRepresentativeResponsesThroughProductionParsingAndActionValidation() throws Exception {
        var dataset = IntentBenchmark.load();
        // One response per parser behavior; wording variants belong to the unchanged live corpus.
        var representatives = Set.of("intent-01", "intent-09", "intent-11", "intent-17", "intent-23", "intent-26",
                "intent-27", "intent-33", "intent-36", "intent-39", "intent-41", "intent-47", "intent-49",
                "intent-50", "intent-51", "intent-56");
        return java.util.stream.StreamSupport.stream(dataset.path("cases").spliterator(), false)
                .filter(sample -> representatives.contains(sample.path("id").asText()))
                .map(sample -> DynamicTest.dynamicTest(sample.path("id").asText() + ": " + sample.path("message").asText(), () -> {
                    var responses = new ArrayDeque<JsonNode>();
                    boolean directCoach = "single".equals(sample.path("expected").path("kind").asText())
                            && "COACH".equals(sample.path("expected").path("expert").asText())
                            && CoachIntentParser.matches(sample.path("message").asText());
                    // Bound diagnosis/optimization (and applyPatches) skip the routing model the same
                    // way direct COACH does — only the action replay remains in the queue.
                    var ctx = sample.path("context");
                    boolean boundResumeForceRoute = ctx.has("diagnosisReportId")
                            || (ctx.has("optimizationReportId") && !ctx.has("jobId"))
                            || "applyPatches".equals(ctx.path("resumeAction").asText(null));
                    boolean skipPlannerReplay = directCoach || boundResumeForceRoute;
                    if (!skipPlannerReplay) responses.add(sample.path("expectedModelJson"));
                    sample.path("actionModelJson").forEach(responses::add);
                    var gateway = new ReplayGateway(responses);
                    var planner = IntentBenchmark.planner(gateway);
                    // Even terminal decisions and composite DAGs go through the real decoder.
                    var decoded = planner.decode(sample.path("expectedModelJson").toString(), sample.path("message").asText());
                    assertThat(IntentBenchmark.correct(sample, decoded)).isTrue();
                    var actual = IntentBenchmark.prepare(planner, sample);
                    assertThat(IntentBenchmark.correct(sample, actual)).as("kind AND expert must match").isTrue();
                    assertThat(actual.needsClarification()).isEqualTo(sample.path("expected").path("needsClarification").asBoolean());
                    if (actual.needsClarification()) {
                        assertThat(actual.clarifyingQuestion()).isNotBlank();
                        assertThat(actual.plan()).isNull();
                        assertThat(sample.path("expected").path("action").asText("clarify")).isEqualTo("clarify");
                    } else if (actual.plan() != null) {
                        assertThat(PlanValidator.validate(actual.plan())).isEqualTo(actual.plan());
                        if (sample.path("expected").has("action")) {
                            assertThat(actual.plan().steps()).singleElement().satisfies(step ->
                                    assertThat(step.inputs()).containsEntry("action", sample.path("expected").path("action").asText()));
                        }
                        if (sample.path("expected").has("inputs")) {
                            assertThat(IntentBenchmark.JSON.<JsonNode>valueToTree(actual.plan().steps().get(0).inputs()))
                                    .isEqualTo(sample.path("expected").path("inputs"));
                        }
                        if (sample.path("expected").has("steps")) {
                            var expected = sample.path("expected").path("steps");
                            assertThat(actual.plan().steps()).hasSize(expected.size());
                            for (int i = 0; i < expected.size(); i++) {
                                var step = actual.plan().steps().get(i);
                                assertThat(step.id()).isEqualTo(expected.get(i).path("id").asText());
                                assertThat(step.agent().name()).isEqualTo(expected.get(i).path("agent").asText());
                                assertThat(IntentBenchmark.JSON.<JsonNode>valueToTree(step.dependsOn())).isEqualTo(expected.get(i).path("dependsOn"));
                                if (expected.get(i).has("action"))
                                    assertThat(step.inputs()).containsEntry("action", expected.get(i).path("action").asText());
                            }
                        }
                    } else assertThat(actual.kind()).isIn("chitchat", "unsafe");
                    assertThat(responses).as("Every pre-authored response was consumed").isEmpty();
                    assertThat(gateway.calls).isEqualTo((skipPlannerReplay ? 0 : 1) + sample.path("actionModelJson").size());
                }));
    }

    @Test
    void corruptedCompositeFixtureStillFailsStrictDecoderAndPlanValidator() throws Exception {
        var sample = IntentBenchmark.load().path("cases").get(54);
        var planner = IntentBenchmark.planner(new ReplayGateway(new ArrayDeque<>()));
        for (String corruption : List.of("duplicate", "missing-dependency", "cycle", "indirect-from", "unknown-field", "trailing-json")) {
            var output = sample.path("expectedModelJson").deepCopy();
            var steps = output.path("plan").path("steps");
            var first = (com.fasterxml.jackson.databind.node.ObjectNode) steps.get(0);
            var second = (com.fasterxml.jackson.databind.node.ObjectNode) steps.get(1);
            switch (corruption) {
                case "duplicate" -> second.put("id", first.path("id").asText());
                case "missing-dependency" -> first.putArray("dependsOn").add("missing");
                case "cycle" -> second.putArray("dependsOn").add(first.path("id").asText());
                case "indirect-from" -> second.putObject("inputs").put("summaryFrom", first.path("id").asText());
                case "unknown-field" -> ((com.fasterxml.jackson.databind.node.ObjectNode) output).put("resourceId", "invented");
                default -> { }
            }
            String text = output + (corruption.equals("trailing-json") ? " {}" : "");
            assertThatThrownBy(() -> planner.decode(text, sample.path("message").asText()))
                    .as(corruption).isInstanceOf(IllegalArgumentException.class);
            if (!Set.of("unknown-field", "trailing-json").contains(corruption))
                assertThatThrownBy(() -> PlanValidator.validate(IntentBenchmark.JSON.treeToValue(output.path("plan"), Plan.class)))
                        .as(corruption).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static final class ReplayGateway extends ModelGateway {
        private final ArrayDeque<JsonNode> responses;
        private int calls;
        ReplayGateway(ArrayDeque<JsonNode> responses) {
            super(null, new org.springframework.mock.env.MockEnvironment());
            this.responses = responses;
        }
        @Override public Flux<ChatResponse> stream(com.offerpilot.llm.ModelTier tier,
                List<org.springframework.ai.chat.messages.Message> messages, double temperature) {
            assertThat(temperature).isEqualTo(0.1);
            assertThat(responses).as("Unexpected retry or extra action classification").isNotEmpty();
            calls++;
            String text = responses.remove().toString();
            int split = text.length() / 2;
            return Flux.just(response(text.substring(0, split)), response(text.substring(split)));
        }
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
