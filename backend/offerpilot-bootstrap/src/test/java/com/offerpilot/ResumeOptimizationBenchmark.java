package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.resume.application.ResumeStructure;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Test-only fixture normalization and review material. No model calls or business persistence. */
final class ResumeOptimizationBenchmark {
    private ResumeOptimizationBenchmark() {}

    static JsonNode load(ObjectMapper json) throws Exception {
        try (var stream = new ClassPathResource("eval/resume-optimize.json").getInputStream()) {
            return json.readTree(stream);
        }
    }

    static ObjectNode source(ObjectMapper json, JsonNode sample) throws Exception {
        ObjectNode source = (ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        for (String section : List.of("education", "experience", "projects", "skills")) source.putArray(section);
        sample.path("resume").fields().forEachRemaining(entry -> source.set(entry.getKey(), entry.getValue().deepCopy()));
        ResumeStructure.validate(source);
        return source;
    }

    static List<String> warnings(JsonNode sample, JsonNode report) {
        List<String> warnings = new ArrayList<>();
        if (sample.path("expectEmpty").asBoolean() && !report.path("patches").isEmpty())
            warnings.add("EXPECTED_EMPTY_SUGGESTIONS");
        for (JsonNode patch : report.path("patches")) {
            String value = patch.path("value").asText().toLowerCase(Locale.ROOT);
            for (JsonNode forbidden : sample.path("forbiddenClaims")) {
                if (value.contains(forbidden.asText().toLowerCase(Locale.ROOT)))
                    warnings.add(patch.path("id").asText() + ":FORBIDDEN_CLAIM:" + forbidden.asText());
            }
        }
        return warnings;
    }

    static ObjectNode humanReview(ObjectMapper json, JsonNode results) {
        ObjectNode review = json.createObjectNode();
        review.put("runId", results.path("runId").asText());
        review.put("resultsSha256", sha256(results.toString()));
        review.put("status", "PENDING_TWO_HUMAN_REVIEWERS");
        review.put("instructions", "两名真人独立审阅；null是未审阅，不能当作通过或拒绝。只在全部建议审阅完整后计算人工指标。争议另行裁决，不能由Agent代填真人结果。");
        var reviewers = review.putArray("reviewers");
        for (int i = 1; i <= 2; i++) {
            ObjectNode reviewer = reviewers.addObject();
            reviewer.put("slot", i).putNull("reviewerName");
            var cases = reviewer.putArray("cases");
            for (JsonNode result : results.path("cases")) {
                ObjectNode entry = cases.addObject();
                entry.put("caseId", result.path("id").asText());
                entry.put("generationStatus", result.path("status").asText());
                if ("COMPLETED".equals(result.path("status").asText()) && result.path("report").path("patches").isEmpty())
                    entry.putNull("emptyAppropriate").putNull("reason");
                var patches = entry.putArray("patches");
                for (JsonNode patch : result.path("report").path("patches"))
                    patches.addObject().put("patchId", patch.path("id").asText()).putNull("factSafe").putNull("adopt").putNull("reason");
            }
        }
        return review;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
