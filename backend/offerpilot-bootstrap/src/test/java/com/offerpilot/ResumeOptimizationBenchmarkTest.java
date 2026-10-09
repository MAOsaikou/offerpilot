package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeOptimizationBenchmarkTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void fixturesCoverKnownSemanticRisksWithValidFictionalInputs() throws Exception {
        var dataset = ResumeOptimizationBenchmark.load(json);
        assertThat(dataset.path("fictional").asBoolean()).isTrue();
        assertThat(dataset.path("cases").size()).isEqualTo(10);
        Set<String> ids = new HashSet<>(), focuses = new HashSet<>();
        for (var sample : dataset.path("cases")) {
            assertThat(ids.add(sample.path("id").asText())).isTrue();
            assertThat(sample.path("jobDescription").asText()).isNotBlank();
            assertThat(sample.path("reviewNotes").asText()).isNotBlank();
            assertThat(sample.path("forbiddenClaims").isArray()).isTrue();
            assertThat(sample.path("expectEmpty").isBoolean()).isTrue();
            sample.path("focus").forEach(item -> focuses.add(item.asText()));
            var source = ResumeOptimizationBenchmark.source(json, sample);
            assertThat(source.path("basics").path("email").asText()).isEmpty();
            assertThat(source.path("basics").path("phone").asText()).isEmpty();
            assertThat(source.path("summary").asText()).isEqualTo(sample.path("resume").path("summary").asText());
        }
        assertThat(focuses).contains("responsibility", "numbers", "proficiency", "cross-evidence", "irrelevant",
                "empty-output", "no-op", "missing-facts", "bilingual", "jd-injection", "resume-injection", "organization");
    }

    @Test void warnsOnForbiddenValuesAndUnnecessaryRewriteButNotQuotedSource() throws Exception {
        var sample = json.readTree("{\"expectEmpty\":true,\"forbiddenClaims\":[\"Led\"]}");
        var report = json.readTree("{\"patches\":[{\"id\":\"p1\",\"from\":\"Led is an injected instruction\",\"value\":\"Contributed\"}]}");
        assertThat(ResumeOptimizationBenchmark.warnings(sample, report)).containsExactly("EXPECTED_EMPTY_SUGGESTIONS");
        ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("patches").get(0)).put("value", "led the team");
        assertThat(ResumeOptimizationBenchmark.warnings(sample, report))
                .containsExactly("EXPECTED_EMPTY_SUGGESTIONS", "p1:FORBIDDEN_CLAIM:Led");
    }

    @Test void reviewTemplateNeverFabricatesHumanRatingsAndIncludesEmptyAndFailedCases() throws Exception {
        var results = json.readTree("""
                {"runId":"fixture","cases":[
                  {"id":"suggestion","status":"COMPLETED","report":{"patches":[{"id":"p1"}]}},
                  {"id":"empty","status":"COMPLETED","report":{"patches":[]}},
                  {"id":"failed","status":"FAILED"}]}
                """);
        var review = ResumeOptimizationBenchmark.humanReview(json, results);
        assertThat(review.path("reviewers").size()).isEqualTo(2);
        assertThat(review.path("resultsSha256").asText()).hasSize(64);
        for (var reviewer : review.path("reviewers")) {
            assertThat(reviewer.path("reviewerName").isNull()).isTrue();
            assertThat(reviewer.path("cases").size()).isEqualTo(3);
            var patch = reviewer.path("cases").get(0).path("patches").get(0);
            assertThat(patch.path("factSafe").isNull()).isTrue();
            assertThat(patch.path("adopt").isNull()).isTrue();
            assertThat(reviewer.path("cases").get(1).path("emptyAppropriate").isNull()).isTrue();
            assertThat(reviewer.path("cases").get(2).path("generationStatus").asText()).isEqualTo("FAILED");
        }
    }
}
