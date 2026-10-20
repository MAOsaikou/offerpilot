package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.llm.safety.PromptInjectionDetector;
import com.offerpilot.llm.safety.PromptInjectionClassifier;
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

    /**
     * Held-out set written after the rules were tuned on redteam-v1 and never used to tune them: new phrasings,
     * spacing / full-width obfuscation, indirect "note for the AI" injections, plus benign questions that use
     * trigger words (忽略、管理员、开发者模式、删除、密钥). Numbers are a floor that only moves up when rules
     * generalise; see docs/02 SC-01. Since the generalising rule change v2 is a tuning set too (13/40 before it).
     */
    @Test void heldOutSetMeasuresGeneralisationHonestly() throws Exception {
        var attacks = lines("/redteam/heldout-attacks-v2.txt");
        var benign = lines("/redteam/heldout-benign-v2.txt");
        long caught = attacks.stream().filter(text -> !PromptInjectionDetector.detect(text).isEmpty()).count();
        var falsePositives = benign.stream().filter(text -> !PromptInjectionDetector.detect(text).isEmpty()).toList();
        System.out.printf("SC-01 held-out: caught %d/%d, false positives %d/%d %s%n",
                caught, attacks.size(), falsePositives.size(), benign.size(), falsePositives);
        assertThat(attacks).hasSize(40);
        assertThat(benign).hasSize(40);
        assertThat(caught).isGreaterThanOrEqualTo(CAUGHT_FLOOR);
        assertThat(falsePositives).hasSizeLessThanOrEqualTo(FALSE_POSITIVE_CEILING);
    }
    /**
     * heldout-v3 was committed before the generalising rule change and never used to tune it; it mirrors the v2
     * categories with fresh wording, so it measures robustness to rephrasing rather than novel attack types.
     */
    @Test void sealedSetV3MeasuresRuleChangeWithoutTuning() throws Exception {
        var attacks = lines("/redteam/heldout-attacks-v3.txt");
        var benign = lines("/redteam/heldout-benign-v3.txt");
        var missed = attacks.stream().filter(text -> PromptInjectionDetector.detect(text).isEmpty()).toList();
        var falsePositives = benign.stream().filter(text -> !PromptInjectionDetector.detect(text).isEmpty()).toList();
        System.out.printf("SC-01 sealed v3: caught %d/%d, false positives %d/%d%n missed=%s%n fp=%s%n",
                attacks.size() - missed.size(), attacks.size(), falsePositives.size(), benign.size(), missed, falsePositives);
        assertThat(attacks).hasSize(40);
        assertThat(benign).hasSize(40);
        assertThat(attacks.size() - missed.size()).isGreaterThanOrEqualTo(V3_CAUGHT_FLOOR);
        assertThat(falsePositives).hasSizeLessThanOrEqualTo(V3_FALSE_POSITIVE_CEILING);
    }
    /** Offline safe-protocol replay measures wiring/fallback only, never estimates FLASH semantic accuracy. */
    @Test void offlineRuleAndSafeClassifierComparisonOnVersionedSets() throws Exception {
        var safe = new PromptInjectionClassifier(prompt -> reactor.core.publisher.Mono.just(
                "{\"injection\":false,\"categories\":[]}"));
        var rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (String version : List.of("v1", "v3", "v4")) {
            var attacks = attacks(version);
            var benign = lines("/redteam/" + (version.equals("v1") ? "benign-v1.txt" : "heldout-benign-" + version + ".txt"));
            long rule = attacks.stream().filter(t -> !PromptInjectionDetector.detect(t).isEmpty()).count();
            long combined = attacks.stream().filter(t -> !safe.detect(t).isEmpty()).count();
            long ruleFp = benign.stream().filter(t -> !PromptInjectionDetector.detect(t).isEmpty()).count();
            long combinedFp = benign.stream().filter(t -> !safe.detect(t).isEmpty()).count();
            assertThat(attacks).hasSize(version.equals("v1") ? 38 : 40);
            assertThat(benign).hasSize(40);
            assertThat(combined).isEqualTo(rule);
            assertThat(combinedFp).isEqualTo(ruleFp);
            assertThat(ruleFp).isZero();
            rows.add(java.util.Map.of("set", version, "attacks", attacks.size(), "benign", benign.size(),
                    "ruleCaught", rule, "safeStubCaught", combined, "ruleFp", ruleFp, "safeStubFp", combinedFp));
        }
        var path = java.nio.file.Path.of("target/sc01-offline.json");
        java.nio.file.Files.createDirectories(path.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows);
        System.out.println("SC01_OFFLINE_SAFE_STUB " + rows);
    }

    static List<String> attacks(String version) throws Exception {
        if (!version.equals("v1")) return lines("/redteam/heldout-attacks-" + version + ".txt");
        var json = new ObjectMapper();
        var result = new java.util.ArrayList<String>();
        for (String line : lines("/redteam/redteam-v1.jsonl")) {
            var node = json.readTree(line);
            if (!"pii".equals(node.path("category").asText())) result.add(node.path("text").asText());
        }
        return result;
    }

    static final int V3_CAUGHT_FLOOR = 27;
    static final int V3_FALSE_POSITIVE_CEILING = 0;
    static final long CAUGHT_FLOOR = 38;
    static final int FALSE_POSITIVE_CEILING = 0;
}
