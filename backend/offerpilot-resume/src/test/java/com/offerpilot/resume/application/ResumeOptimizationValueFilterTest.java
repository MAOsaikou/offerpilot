package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class ResumeOptimizationValueFilterTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String FROM = "负责后端开发，使用Java实现登录功能，使用Redis优化缓存。";
    private static final String VALUE = "后端开发：使用Java实现登录功能；使用Redis优化缓存。";

    @Test void filtersIdenticalTextAfterWidthCaseAndUnicodeWhitespaceNormalization() {
        var report = report("　ＪＡＶＡ\t缓存\n开发　", "java 缓存 开发");
        var filtered = ResumeOptimizationValueFilter.filter(report);
        assertThat(filtered.path("patches")).isEmpty();
        assertThat(filtered.path("filteredPatches").size()).isEqualTo(1);
        assertThat(filtered.path("filteredPatches").get(0)).isEqualTo(json.createObjectNode()
                .put("id", "p1").put("path", "/summary").put("reason", "NORMALIZED_EQUAL"));
    }

    @Test void filtersTinyChineseSynonymSwapAfterValidation() throws Exception {
        var report = report(FROM, FROM.replace("负责", "承担"));
        validate(report, FROM);
        var filtered = ResumeOptimizationValueFilter.filter(report);
        assertThat(filtered.path("patches")).isEmpty();
        assertThat(filtered.path("filteredPatches").get(0).path("reason").asText()).isEqualTo("TINY_EDIT");
    }

    @ParameterizedTest
    @CsvSource({
        "abcdefghi, abcdefghX, false",
        "abcdefghij, abcdefghiX, true",
        "abcdefghijklmnopqrs, abcdefghijklmnopqXY, false",
        "abcdefghijklmnopqrst, abcdefghijklmnopqrXY, true",
        "abcdefghijklmnopqrstuvwxyzabcd, abcdefghijklmnopqrstuvwxyzaXYZ, false",
        "abcdefghij, abcdefghijX, true",
        "abcdefghij, abcdefghi, true",
        "😀😀😀😀😀😀😀😀😀, 😀😀😀😀😀😀😀😀😁, false"
    })
    void respectsRelativeAbsoluteAndCodePointBoundaries(String from, String value, boolean dropped) {
        var filtered = ResumeOptimizationValueFilter.filter(report(from, value));
        assertThat(filtered.path("patches").isEmpty()).isEqualTo(dropped);
        assertThat(filtered.path("filteredPatches").size()).isEqualTo(dropped ? 1 : 0);
    }

    @Test void preservesRetainedIdsOrderAndValidationWithoutMutatingInput() throws Exception {
        var report = report(" Java ", "java");
        ObjectNode kept = (ObjectNode) report(FROM, VALUE).path("patches").get(0);
        kept.put("id", "p2").put("path", "/projects/0/bullets/0");
        report.withArray("patches").add(kept);
        ObjectNode source = (ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        source.put("summary", " Java ");
        ((ObjectNode) source.path("projects").get(0)).withArray("bullets").add(FROM);
        ResumeOptimizationValidator.validate(report, source, "后端开发");
        var original = report.deepCopy();
        var filtered = ResumeOptimizationValueFilter.filter(report);
        assertThat(report).isEqualTo(original);
        assertThat(filtered.path("patches").size()).isEqualTo(1);
        assertThat(filtered.path("patches").get(0)).isEqualTo(kept);
        assertThat(filtered.path("filteredPatches").get(0).path("id").asText()).isEqualTo("p1");
        ResumeOptimizationValidator.validate(modelOutput(filtered), source, "后端开发");
        ((ObjectNode) filtered.path("patches").get(0)).put("value", "changed");
        assertThat(report).isEqualTo(original);
    }

    private ObjectNode report(String from, String value) {
        ObjectNode report = json.createObjectNode().put("summary", "突出已有后端贡献");
        report.putArray("patches").addObject().put("id", "p1").put("op", "replace").put("path", "/summary")
                .put("from", from).put("value", value).put("reason", "明确已有贡献")
                .put("jdEvidence", "后端开发").put("needsUserConfirmation", false);
        return report;
    }

    private ObjectNode modelOutput(ObjectNode filtered) {
        ObjectNode result = filtered.deepCopy();
        result.remove("filteredPatches");
        return result;
    }

    private void validate(ObjectNode report, String from) throws Exception {
        ObjectNode source = (ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        source.put("summary", from);
        ResumeOptimizationValidator.validate(report, source, "后端开发");
    }
}
