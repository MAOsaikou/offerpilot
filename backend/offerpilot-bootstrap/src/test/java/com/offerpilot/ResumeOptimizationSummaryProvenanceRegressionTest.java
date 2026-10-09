package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.resume.application.ResumeOptimizationValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Baseline first-attempt failures, replayed offline. Production validation must stay strict. */
class ResumeOptimizationSummaryProvenanceRegressionTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest(name = "baseline summary borrows numbers: {0}")
    @ValueSource(strings = {"java-contributor", "cross-project-numbers", "bilingual-contributor"})
    void rejectsBaselineFirstAttemptWhileOtherPatchesRemainValid(String caseId) throws Exception {
        var fixture = fixture(caseId);
        var output = fixture.path("firstAttemptOutput");
        var source = fixture.path("source");
        String jd = fixture.path("jobDescription").asText();
        assertThat(fixture.path("id").asText()).isEqualTo(caseId);
        assertThat(fixture.path("failureKind").asText()).isEqualTo("summary-borrowed-numbers-from-other-entries");
        assertThatThrownBy(() -> ResumeOptimizationValidator.validate(output, source, jd))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.STRUCTURED_OUTPUT_INVALID));

        // Isolate the offending path: the other baseline patches still pass unchanged.
        ObjectNode withoutSummary = output.deepCopy();
        var remaining = withoutSummary.putArray("patches");
        int summaries = 0;
        for (var patch : output.path("patches")) {
            if ("/summary".equals(patch.path("path").asText())) summaries++;
            else remaining.add(patch.deepCopy());
        }
        assertThat(summaries).isEqualTo(1);
        ResumeOptimizationValidator.validate(withoutSummary, source, jd);
    }

    @Test void acceptsQualitativeSummaryWithoutImportingProjectNumbers() throws Exception {
        var fixture = fixture("java-contributor");
        ObjectNode output = fixture.path("firstAttemptOutput").deepCopy();
        ObjectNode patch = (ObjectNode) output.path("patches").get(0).deepCopy();
        assertThat(patch.path("path").asText()).isEqualTo("/summary");
        patch.put("value", "软件工程本科生，在校园借阅项目中参与后端开发。");
        output.putArray("patches").add(patch);
        ResumeOptimizationValidator.validate(output, fixture.path("source"), fixture.path("jobDescription").asText());
    }

    private JsonNode fixture(String caseId) throws Exception {
        try (var stream = new ClassPathResource("eval/summary-provenance-failures/" + caseId + ".json").getInputStream()) {
            return json.readTree(stream);
        }
    }
}
