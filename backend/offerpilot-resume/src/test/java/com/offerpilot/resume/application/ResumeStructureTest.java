package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class ResumeStructureTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void defaultsMissingLegacyTitleAndPreservesExplicitTitle() throws Exception {
        var root = (ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        var basics = (ObjectNode) root.path("basics");
        basics.remove("title");
        assertThat(ResumeStructure.validate(root).at("/basics/title").asText()).isEmpty();
        assertThat(basics.has("title")).isTrue();
        basics.put("title", "求职意向原文：Java 开发实习生");
        assertThat(ResumeStructure.validate(root).at("/basics/title").asText())
            .isEqualTo("求职意向原文：Java 开发实习生");
        basics.put("title", "字".repeat(5000));
        assertThatCode(() -> ResumeStructure.validate(root)).doesNotThrowAnyException();
        basics.put("title", "字".repeat(5001));
        assertThatThrownBy(() -> ResumeStructure.validate(root)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "42", "true", "[]", "{}"})
    void rejectsNonStringTitle(String value) throws Exception {
        var root = (ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        ((ObjectNode) root.path("basics")).set("title", json.readTree(value));
        assertThatThrownBy(() -> ResumeStructure.validate(root)).isInstanceOf(BusinessException.class);
    }
}
