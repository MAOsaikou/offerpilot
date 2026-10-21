package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.resume.application.ResumeDocumentReader;
import com.offerpilot.resume.application.ResumeExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RS-02 English public benchmark (backend/eval/resume-en-benchmark). Runs every sample listed in
 * RESUME_EN_DIR/manifest.json through the same reader + extractor as the upload path and writes the raw
 * structured output (or the failure) to RESUME_EN_DIR/outputs.json. Scoring lives in score.py so the
 * dataset-specific field mapping stays outside production code. Failures are recorded, never retried.
 */
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_RESUME_EN", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".*\\S.*")
class ResumeEnglishBenchmarkLiveDumpTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test void dumpStructuredOutputs() throws Exception {
        Path dir = Path.of(System.getenv("RESUME_EN_DIR"));
        var manifest = JSON.readTree(dir.resolve("manifest.json").toFile());
        String model = System.getenv().getOrDefault("RESUME_RECALL_MODEL", "deepseek-flash");
        var tier = new OfferPilotProperties.Llm.Tier("deepseek", model, Duration.ofSeconds(120), true);
        var properties = new OfferPilotProperties(null, null, null, null, null,
                new OfferPilotProperties.Llm("FLASH", Map.of("FLASH", tier, "VISION_ALT", tier), Map.of()), null, null);
        var environment = new MockEnvironment().withProperty("spring.ai.deepseek.api-key", System.getenv("DEEPSEEK_API_KEY"))
                .withProperty("spring.ai.deepseek.base-url", System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"));
        var extractor = new ResumeExtractor(new ModelGateway(properties, environment), JSON, new ClassPathResource("prompts/resume/extract.st"));
        var reader = new ResumeDocumentReader();
        var only = System.getenv("RESUME_EN_ONLY");
        Path out = dir.resolve(System.getenv().getOrDefault("RESUME_EN_OUT", "outputs.json"));
        var outputs = Files.exists(out) ? (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(out.toFile()) : JSON.createObjectNode();
        for (var sample : manifest.path("samples")) {
            String id = sample.path("id").asText();
            if (only != null && !only.isBlank() && !java.util.Set.of(only.split(",")).contains(id)) continue;
            if (outputs.has(id)) continue; // resumable: a recorded result (including a failure) is final
            var entry = JSON.createObjectNode().put("model", model);
            long started = System.nanoTime();
            try {
                byte[] bytes = Files.readAllBytes(dir.resolve(sample.path("file").asText()));
                var document = reader.read(bytes, reader.mime(sample.path("file").asText(), bytes));
                entry.put("channel", document.channel());
                entry.set("structured", extractor.extractResult(document).structured());
            } catch (Exception failed) {
                entry.put("failure", failed.getClass().getSimpleName() + ": " + String.valueOf(failed.getMessage()).lines().findFirst().orElse(""));
            }
            entry.put("ms", (System.nanoTime() - started) / 1_000_000);
            outputs.set(id, entry);
            Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(outputs));
            System.out.println("RS-02-EN " + id + " " + (entry.has("failure") ? entry.get("failure").asText() : "ok") + " " + entry.get("ms") + "ms");
        }
        assertThat(outputs.size()).isPositive();
    }
}
