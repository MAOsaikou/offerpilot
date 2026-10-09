package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.resume.application.ResumeOptimizer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import org.springframework.retry.support.RetryTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit paid-model opt-in. Calls production optimizer/gateway; never connects to business data. */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_OPTIMIZE", matches = "true")
class ResumeOptimizationLiveEvaluationTest {
    @Test void evaluatesTenFictionalResumeJobPairsAndPreservesEvidence() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertThat(key != null && !key.isBlank()).as("DEEPSEEK_API_KEY must be present").isTrue();
        String base = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("LLM_MODEL_FLASH", "deepseek-flash");
        var tier = new OfferPilotProperties.Llm.Tier("deepseek", model, Duration.ofSeconds(90), false);
        var properties = new OfferPilotProperties(null, null, null, null, null,
                new OfferPilotProperties.Llm("FLASH", Map.of("FLASH", tier), Map.of()), null, null);
        var environment = new MockEnvironment().withProperty("spring.ai.deepseek.api-key", key)
                .withProperty("spring.ai.deepseek.base-url", base);
        var json = new ObjectMapper();
        var gateway = new RecordingGateway(properties, environment);
        var prompt = new ClassPathResource("prompts/resume/optimize.st");
        var optimizer = new ResumeOptimizer(gateway, json, prompt);
        var dataset = ResumeOptimizationBenchmark.load(json);
        ObjectNode results = json.createObjectNode();
        String runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
        Path output = Path.of(System.getenv().getOrDefault("OFFERPILOT_OPTIMIZE_EVAL_OUT",
                "target/resume-optimize-eval/" + runId)).toAbsolutePath();
        for (String name : List.of("results.json", "human-review.json", "prompt.st"))
            assertThat(Files.exists(output.resolve(name))).as("Use a new output directory to preserve prior evidence").isFalse();
        Files.createDirectories(output);
        results.put("runId", runId).put("startedAt", Instant.now().toString())
                .put("datasetVersion", dataset.path("version").asText())
                .put("datasetSha256", ResumeOptimizationBenchmark.sha256(dataset.toString()))
                .put("promptSha256", ResumeOptimizationBenchmark.sha256(prompt.getContentAsString(StandardCharsets.UTF_8)))
                .put("sourceRevision", System.getProperty("offerpilot.eval.revision", "UNRECORDED"))
                .put("model", model).put("tier", "FLASH").put("temperature", 0.3)
                .put("scope", "10 fictional pairs; production optimizer and gateway; no database; one bounded production repair; no provider retry")
                .put("humanStatus", "PENDING_TWO_HUMAN_REVIEWERS");
        results.putNull("humanFactSafeRate").putNull("humanAdoptionRate");
        ArrayNode cases = results.putArray("cases");
        int failures = 0, boundaryFailures = 0;
        for (var sample : dataset.path("cases")) {
            var source = ResumeOptimizationBenchmark.source(json, sample);
            var entry = cases.addObject();
            entry.put("id", sample.path("id").asText()).put("label", sample.path("label").asText());
            entry.set("fixture", sample); entry.set("source", source);
            gateway.attempts = entry.putArray("attempts");
            long started = System.nanoTime();
            try {
                var report = optimizer.optimize(source, sample.path("jobDescription").asText(), ModelTier.FLASH)
                        .block(Duration.ofSeconds(100));
                entry.put("status", "COMPLETED"); entry.set("report", report);
                var warnings = ResumeOptimizationBenchmark.warnings(sample, report);
                entry.set("automaticWarnings", json.valueToTree(warnings));
                entry.put("automaticBoundaryPass", warnings.isEmpty());
                if (!warnings.isEmpty()) boundaryFailures++;
            } catch (Exception ex) {
                entry.put("status", "FAILED").put("errorCode", ex instanceof BusinessException business
                        ? business.errorCode().name() : "EVALUATION_ERROR");
                failures++;
            }
            entry.put("wallDurationMs", (System.nanoTime() - started) / 1_000_000);
            entry.put("modelAttempts", gateway.attempts.size());
            persist(json, output, "results.json", results);
            System.out.printf("OPTIMIZE_EVAL %s status=%s attempts=%d durationMs=%d%n", entry.path("id").asText(),
                    entry.path("status").asText(), gateway.attempts.size(), entry.path("wallDurationMs").asLong());
        }
        results.put("finishedAt", Instant.now().toString()).put("casesTotal", cases.size())
                .put("generationFailures", failures).put("automaticBoundaryFailures", boundaryFailures);
        persist(json, output, "results.json", results);
        persist(json, output, "human-review.json", ResumeOptimizationBenchmark.humanReview(json, results));
        Files.writeString(output.resolve("prompt.st"), prompt.getContentAsString(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
        System.out.println("OPTIMIZE_EVAL results=" + output);
        // Always preserve all cases before assertions; a failed quality run is valid evaluation evidence.
        assertThat(failures).as("Production generation failures; see results.json").isZero();
        assertThat(boundaryFailures).as("Predeclared automatic boundary failures; not human acceptance").isZero();
    }

    private static void persist(ObjectMapper json, Path output, String name, ObjectNode node) throws Exception {
        Files.writeString(output.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }

    /** Observes the real gateway without changing its prompts, decoding, validator, or repair behavior. */
    private static final class RecordingGateway extends ModelGateway {
        private ArrayNode attempts;

        RecordingGateway(OfferPilotProperties properties, MockEnvironment environment) {
            super(properties, environment, null, RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build());
        }

        @Override public Flux<ChatResponse> stream(ModelTier tier, List<Message> messages, double temperature) {
            ObjectNode trace = attempts.addObject();
            trace.put("attempt", attempts.size()).put("completed", false);
            trace.putNull("inputTokens").putNull("outputTokens");
            StringBuilder text = new StringBuilder();
            long started = System.nanoTime();
            return super.stream(tier, messages, temperature).doOnNext(response -> {
                if (response.getResult() != null && response.getResult().getOutput().getText() != null)
                    text.append(response.getResult().getOutput().getText());
                var usage = response.getMetadata().getUsage();
                if (usage != null) {
                    if (usage.getPromptTokens() != null) trace.put("inputTokens", usage.getPromptTokens());
                    if (usage.getCompletionTokens() != null) trace.put("outputTokens", usage.getCompletionTokens());
                }
                trace.put("rawOutput", text.toString()).put("durationMs", (System.nanoTime() - started) / 1_000_000);
            }).doOnComplete(() -> trace.put("completed", true))
              .doOnError(error -> trace.put("transportFailed", true))
              .doOnCancel(() -> trace.put(trace.path("completed").asBoolean()
                      ? "cancelledAfterCompletion" : "cancelled", true));
        }
    }
}
