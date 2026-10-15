package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.llm.safety.PromptInjectionDetector;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-01 rule layer on the redteam-v1 attack set and a benign career-question set. The rule layer only marks
 * suspected fragments for the model; end-to-end behaviour stays covered by RedteamRegressionTest.
 * Known misses: RT-14 and RT-20 carry only contact details / ID numbers (handled by SC-04 masking).
 */
class PromptInjectionCoverageTest {
    static List<String> lines(String resource) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                PromptInjectionCoverageTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).toList();
        }
    }

    @Test void ruleLayerFlagsRedteamAttacksWithoutFlaggingOrdinaryQuestions() throws Exception {
        var json = new ObjectMapper();
        var attacks = lines("/redteam/redteam-v1.jsonl").stream().map(line -> {
            try { return json.readTree(line); } catch (Exception ex) { throw new IllegalStateException(ex); }
        }).filter(node -> !"pii".equals(node.path("category").asText())).toList();
        var missed = attacks.stream().filter(node -> PromptInjectionDetector.detect(node.path("text").asText()).isEmpty())
                .map(node -> node.path("id").asText()).toList();
        assertThat(missed).containsExactlyInAnyOrder("RT-14", "RT-20");
        assertThat(attacks).hasSize(38);

        var benign = lines("/redteam/benign-v1.txt");
        assertThat(benign).hasSizeGreaterThanOrEqualTo(40);
        assertThat(benign).allSatisfy(text -> assertThat(PromptInjectionDetector.detect(text)).as(text).isEmpty());
    }
}
