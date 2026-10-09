package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.resume.application.ResumeOptimizationValidator;
import com.offerpilot.resume.application.ResumeOptimizationValueFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Replays saved fictional output through production validation. Does not call the model. */
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_OPTIMIZE_EVAL_REPLAY", matches = ".+")
class ResumeOptimizationEvidenceReplayTest {
    @Test void recordedAttemptValidityAndTokenTotalsMatchDeliveredReports() throws Exception {
        var json = new ObjectMapper();
        var results = json.readTree(Path.of(System.getenv("OFFERPILOT_OPTIMIZE_EVAL_REPLAY")).toFile());
        assertThat(results.path("cases").size()).isEqualTo(10);
        int rejected = 0, expectedRejected = 0;
        for (var sample : results.path("cases")) {
            String jd = sample.path("fixture").path("jobDescription").asText();
            assertThat(sample.path("status").asText()).isEqualTo("COMPLETED");
            ObjectNode report = sample.path("report").deepCopy();
            report.remove(java.util.List.of("promptVersion", "model", "modelTier", "durationMs", "inputTokens", "outputTokens"));
            boolean valueFiltered = report.has("filteredPatches");
            var filteredMetadata = report.remove("filteredPatches");
            ResumeOptimizationValidator.validate(report, sample.path("source"), jd);
            int in = 0, out = 0;
            expectedRejected += sample.path("modelAttempts").asInt() - 1;
            for (var attempt : sample.path("attempts")) {
                String text = attempt.path("rawOutput").asText().strip()
                        .replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                if (attempt.path("attempt").asInt() < sample.path("modelAttempts").asInt()) {
                    assertThatThrownBy(() -> {
                        var raw = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
                        ResumeOptimizationValidator.validate(raw, sample.path("source"), jd);
                    }).isInstanceOfAny(BusinessException.class, com.fasterxml.jackson.core.JsonProcessingException.class);
                    rejected++;
                } else {
                    var raw = json.readTree(text);
                    ResumeOptimizationValidator.validate(raw, sample.path("source"), jd);
                    if (valueFiltered) {
                        var filtered = ResumeOptimizationValueFilter.filter((ObjectNode) raw);
                        assertThat(filtered.remove("filteredPatches")).isEqualTo(filteredMetadata);
                        assertThat(filtered).isEqualTo(report);
                    } else assertThat(raw).isEqualTo(report);
                }
                in += attempt.path("inputTokens").asInt(); out += attempt.path("outputTokens").asInt();
            }
            assertThat(in).isEqualTo(sample.path("report").path("inputTokens").asInt());
            assertThat(out).isEqualTo(sample.path("report").path("outputTokens").asInt());
        }
        assertThat(rejected).isEqualTo(expectedRejected);
    }
}
