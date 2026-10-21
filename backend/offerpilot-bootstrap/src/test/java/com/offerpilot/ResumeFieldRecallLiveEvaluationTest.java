package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RS-02 field recall on the generated 24-resume corpus (backend/eval/resume-recall/generate.py).
 * Explicit opt-in only: sends the invented resumes to the configured DeepSeek tier through the same
 * reader + extractor the upload path uses, and writes per-field recall with every miss to
 * target/resume-recall-report.json.
 */
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_RESUME_RECALL", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".*\\S.*")
class ResumeFieldRecallLiveEvaluationTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test void fieldRecallOnGeneratedCorpus() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("RESUME_RECALL_DIR", "../eval/resume-recall/out"));
        var truth = JSON.readTree(dir.resolve("truth.json").toFile());
        var manifestPath = dir.resolve("manifest.json");
        var manifest = Files.exists(manifestPath) ? JSON.readTree(manifestPath.toFile()) : JSON.createObjectNode();
        String provenance = manifest.path("provenance").asText("unspecified");
        if ("consented_real".equals(provenance)) {
            assertThat(manifest.path("samples").isArray()).isTrue();
            assertThat(manifest.path("samples").size()).isEqualTo(truth.size());
            var declared = new HashMap<String, String>();
            manifest.path("samples").forEach(sample -> assertThat(declared.put(sample.path("id").asText(), sample.path("file").asText())).isNull());
            truth.forEach(sample -> assertThat(declared.get(sample.path("id").asText())).isEqualTo(sample.path("file").asText()));
        }
        for (var sample : manifest.path("samples")) {
            var file = dir.resolve(sample.path("file").asText()).normalize();
            assertThat(file.startsWith(dir.normalize())).as("sample must stay inside corpus").isTrue();
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            assertThat(digest).as(sample.path("id").asText() + " source fingerprint").isEqualTo(sample.path("sha256").asText());
        }
        String base = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("RESUME_RECALL_MODEL", "deepseek-flash");
        var tier = new OfferPilotProperties.Llm.Tier("deepseek", model, Duration.ofSeconds(120), true);
        var properties = new OfferPilotProperties(null, null, null, null, null,
                new OfferPilotProperties.Llm("FLASH", Map.of("FLASH", tier, "VISION_ALT", tier), Map.of()), null, null);
        var environment = new MockEnvironment().withProperty("spring.ai.deepseek.api-key", System.getenv("DEEPSEEK_API_KEY"))
                .withProperty("spring.ai.deepseek.base-url", base);
        var extractor = new ResumeExtractor(new ModelGateway(properties, environment), JSON, new ClassPathResource("prompts/resume/extract.st"));
        var reader = new ResumeDocumentReader();
        boolean textOnly = "true".equals(System.getenv("RESUME_RECALL_TEXT_ONLY"));
        String only = System.getenv("RESUME_RECALL_ONLY");

        assertThat(truth.isArray()).isTrue();
        var sampleIds = new HashSet<String>();
        truth.forEach(sample -> assertThat(sampleIds.add(sample.path("id").asText())).as("unique sample IDs").isTrue());
        var independent = new HashSet<String>();
        truth.forEach(sample -> independent.add(sample.path("sourceId").asText(sample.path("id").asText())));
        assertThat(independent.size()).as("At least 20 independently annotated resumes, derivatives do not count").isGreaterThanOrEqualTo(20);
        var score = new ResumeFieldRecallScore();
        var outputs = JSON.createObjectNode();
        for (JsonNode expected : truth) {
            String id = expected.path("id").asText();
            if (only != null && !Set.of(only.split(",")).contains(id)) continue;
            JsonNode actual;
            try {
                byte[] bytes = Files.readAllBytes(dir.resolve(expected.path("file").asText()));
                var document = reader.read(bytes, reader.mime(expected.path("file").asText(), bytes));
                if (textOnly) document = new ResumeDocumentReader.Document(document.text(), List.of(), document.channel());
                actual = extractor.extractResult(document).structured();
            } catch (Exception failed) {
                score.failures.add(id + ": " + failed.getClass().getSimpleName());
                actual = JSON.createObjectNode();
            }
            outputs.set(id, actual);
            score.resume(id + "/" + expected.path("layout").asText(), expected, actual);
        }
        var report = JSON.createObjectNode();
        report.put("model", model).put("textOnly", textOnly).put("overall", score.overall())
                .put("independentResumes", independent.size()).put("samples", outputs.size())
                .put("corpusSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("truth.json")))));
        report.put("corpusProvenance", provenance);
        report.put("passesFieldRecallGate", score.failures.isEmpty() && score.overall() >= 0.9);
        if (only != null || textOnly || !"consented_real".equals(provenance)) report.putNull("passesLiveGate");
        else report.put("passesLiveGate", score.failures.isEmpty() && score.overall() >= 0.9);
        report.set("fields", JSON.valueToTree(score.recall()));
        report.set("misses", JSON.valueToTree(score.misses));
        report.set("failures", JSON.valueToTree(score.failures));
        report.set("outputs", outputs);
        Files.writeString(Path.of("target/resume-recall-report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("RS-02 recall overall=" + score.overall() + " fields=" + score.recall());
        assertThat(outputs.size()).as("selected samples").isPositive();
        assertThat(score.failures).isEmpty();
        if (only == null && !textOnly) assertThat(score.overall()).as("RS-02 field recall").isGreaterThanOrEqualTo(0.9);
    }

}
