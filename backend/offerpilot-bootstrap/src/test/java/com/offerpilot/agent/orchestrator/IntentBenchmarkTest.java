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
    Stream<DynamicTest> replaysAllSixtyResponsesThroughProductionParsingAndActionValidation() throws Exception {
        var dataset = IntentBenchmark.load();
        return java.util.stream.StreamSupport.stream(dataset.path("cases").spliterator(), false)
                .map(sample -> DynamicTest.dynamicTest(sample.path("id").asText() + ": " + sample.path("message").asText(), () -> {
                    var responses = new ArrayDeque<JsonNode>();
                    boolean directCoach = "single".equals(sample.path("expected").path("kind").asText())
                            && "COACH".equals(sample.path("expected").path("expert").asText())
                            && CoachIntentParser.matches(sample.path("message").asText());
                    if (!directCoach) responses.add(sample.path("expectedModelJson"));
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
                    assertThat(gateway.calls).isEqualTo((directCoach ? 0 : 1) + sample.path("actionModelJson").size());
                }));
    }

    @Test
    void corpusCoverageIsFixedAndIncludesAllFiveExpertsAndChineseFollowUps() throws Exception {
        var cases = IntentBenchmark.load().path("cases");
        assertThat(cases).hasSize(60);
        Set<String> ids = new HashSet<>(), messages = new HashSet<>(), experts = new HashSet<>();
        Map<String, Integer> categories = new HashMap<>(), kinds = new HashMap<>();
        int histories = 0, contexts = 0;
        for (var sample : cases) {
            assertThat(ids.add(sample.path("id").asText())).isTrue();
            assertThat(messages.add(sample.path("message").asText())).isTrue();
            assertThat(sample.path("message").asText()).matches("(?s).*[\\p{IsHan}].*");
            assertThat(sample.path("expected").has("expert")).isTrue();
            assertThat(sample.path("expected").has("kind")).isTrue();
            categories.merge(sample.path("category").asText(), 1, Integer::sum);
            kinds.merge(sample.path("expected").path("kind").asText(), 1, Integer::sum);
            if (!sample.path("expected").path("expert").isNull()) experts.add(sample.path("expected").path("expert").asText());
            if (sample.has("history")) histories++;
            if (sample.has("context")) contexts++;
        }
        assertThat(categories).isEqualTo(Map.of("rewrite", 10, "diagnose", 8, "search", 8, "match", 6,
                "other-expert", 8, "chitchat", 6, "boundary", 8, "composite", 6));
        assertThat(kinds).isEqualTo(Map.of("single", 44, "composite", 6, "chitchat", 6, "unsafe", 4));
        assertThat(experts).containsExactlyInAnyOrder("RESUME", "JOB", "CAREER_DISCOVERY", "INTERVIEWER", "COACH");
        assertThat(histories).isGreaterThanOrEqualTo(5);
        assertThat(contexts).isGreaterThanOrEqualTo(20);
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
            super(null, null, null, null);
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
