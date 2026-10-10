package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeOptimizationBenchmarkTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void warnsOnForbiddenValuesAndUnnecessaryRewriteButNotQuotedSource() throws Exception {
        var sample = json.readTree("{\"expectEmpty\":true,\"forbiddenClaims\":[\"Led\"]}");
        var report = json.readTree("{\"patches\":[{\"id\":\"p1\",\"from\":\"Led is an injected instruction\",\"value\":\"Contributed\"}]}");
        assertThat(ResumeOptimizationBenchmark.warnings(sample, report)).containsExactly("EXPECTED_EMPTY_SUGGESTIONS");
        ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("patches").get(0)).put("value", "led the team");
        assertThat(ResumeOptimizationBenchmark.warnings(sample, report))
                .containsExactly("EXPECTED_EMPTY_SUGGESTIONS", "p1:FORBIDDEN_CLAIM:Led");
    }

}
