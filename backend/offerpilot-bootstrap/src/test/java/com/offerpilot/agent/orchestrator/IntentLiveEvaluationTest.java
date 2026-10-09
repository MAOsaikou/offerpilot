package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Paid network calls require BOTH opt-in and a nonblank key; no Spring context or business database. */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_INTENT", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".*\\S.*")
class IntentLiveEvaluationTest {
    @Test
    void measuresRealKindAndExpertAccuracyAcrossAllSixtyMessages() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        String base = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("LLM_MODEL_CHEAP", "deepseek-flash");
        var tier = new OfferPilotProperties.Llm.Tier("deepseek", model, Duration.ofSeconds(60), false);
        var properties = new OfferPilotProperties(null, null, null, null, null,
                new OfferPilotProperties.Llm("CHEAP", Map.of("CHEAP", tier), Map.of()), null);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("deepseek", DeepSeekChatModel.builder()
                .deepSeekApi(DeepSeekApi.builder().baseUrl(base).apiKey(key).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build()).build());
        var gateway = new RecordingGateway(properties, beans,
                new MockEnvironment().withProperty("spring.ai.deepseek.api-key", key).withProperty("spring.ai.deepseek.base-url", base));
        var planner = IntentBenchmark.planner(gateway);
        var dataset = IntentBenchmark.load();
        assertThat(dataset.path("cases")).hasSize(60);
        Path directory = Path.of("target/intent-eval", Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID());
        Files.createDirectories(directory);
        Path output = directory.resolve("results.json");
        ObjectNode result = IntentBenchmark.JSON.createObjectNode();
        result.put("startedAt", Instant.now().toString()).put("model", model).put("tier", "CHEAP").put("temperature", 0.1)
                .put("datasetVersion", dataset.path("version").asText()).put("datasetSha256", sha256(dataset.toString()))
                .put("sourceRevision", System.getProperty("offerpilot.eval.revision", "UNRECORDED"))
                .put("scope", "Live production IntentPlanner and action parsers; 60 fictional messages; no expert execution or database; kind AND expert accuracy; failures count as incorrect");
        var fingerprints = result.putObject("promptSha256");
        for (String name : List.of("plan", "job-intent", "resume-intent"))
            fingerprints.put(name, sha256(new ClassPathResource("prompts/agent/" + name + ".st").getContentAsString(StandardCharsets.UTF_8)));
        ArrayNode entries = result.putArray("cases");
        int correct = 0, failed = 0;
        for (var sample : dataset.path("cases")) {
            var entry = entries.addObject();
            entry.put("id", sample.path("id").asText());
            entry.set("fixture", sample);
            gateway.attempts = entry.putArray("attempts");
            long started = System.nanoTime();
            try {
                var actual = IntentBenchmark.prepare(planner, sample);
                entry.set("actual", IntentBenchmark.JSON.valueToTree(actual));
                boolean matches = IntentBenchmark.correct(sample, actual);
                entry.put("correctKindAndExpert", matches).put("status", "COMPLETED");
                if (matches) correct++;
            } catch (Exception ex) {
                entry.put("status", "FAILED").put("correctKindAndExpert", false).put("errorCode", "EVALUATION_ERROR");
                failed++;
            }
            entry.put("wallDurationMs", (System.nanoTime() - started) / 1_000_000);
            result.put("evaluated", entries.size()).put("correct", correct).put("failures", failed);
            Files.writeString(output, IntentBenchmark.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            System.out.printf("INTENT_EVAL %s status=%s correct=%s%n", sample.path("id").asText(),
                    entry.path("status").asText(), entry.path("correctKindAndExpert").asBoolean());
        }
        double accuracy = correct / 60.0;
        result.put("finishedAt", Instant.now().toString()).put("total", 60).put("accuracy", accuracy)
                .put("target", 0.9).put("targetMet", accuracy >= 0.9);
        Files.writeString(output, IntentBenchmark.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("INTENT_EVAL results=" + output.toAbsolutePath());
        assertThat(accuracy).as("Real kind AND expert accuracy; see %s", output).isGreaterThanOrEqualTo(0.9);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class RecordingGateway extends ModelGateway {
        private ArrayNode attempts;
        RecordingGateway(OfferPilotProperties properties, DefaultListableBeanFactory beans, MockEnvironment environment) {
            super(properties, beans.getBeanProvider(DeepSeekChatModel.class), beans.getBeanProvider(OpenAiChatModel.class), environment);
        }
        @Override public Flux<ChatResponse> stream(ModelTier tier, List<Message> messages, double temperature) {
            var trace = attempts.addObject().put("completed", false);
            trace.putNull("inputTokens").putNull("outputTokens");
            var text = new StringBuilder();
            return super.stream(tier, messages, temperature).doOnNext(response -> {
                if (response.getResult() != null && response.getResult().getOutput().getText() != null)
                    text.append(response.getResult().getOutput().getText());
                trace.put("rawOutput", text.toString());
                var usage = response.getMetadata().getUsage();
                if (usage != null) {
                    if (usage.getPromptTokens() != null) trace.put("inputTokens", usage.getPromptTokens());
                    if (usage.getCompletionTokens() != null) trace.put("outputTokens", usage.getCompletionTokens());
                }
            }).doOnComplete(() -> trace.put("completed", true)).doOnError(error -> trace.put("transportFailed", true));
        }
    }
}
