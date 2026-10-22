package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.offerpilot.career.application.CareerRecommendationService.*;

class CareerRerankTest {
    final ObjectMapper json = new ObjectMapper();
    Revision parent() throws Exception {
        var seed = json.readTree(Files.readString(Path.of("../offerpilot-bootstrap/src/main/resources/seed/occupations.json")));
        var items = new ArrayList<Item>();
        for (String id : List.of("occ_frontend_dev", "occ_backend_dev", "occ_data_analyst")) {
            var node = java.util.stream.StreamSupport.stream(seed.path("occupations").spliterator(), false)
                    .filter(n -> n.path("id").asText().equals(id)).findFirst().orElseThrow();
            Map<String, Double> axes = new LinkedHashMap<>();
            for (char c : "RIASEC".toCharArray()) axes.put(String.valueOf(c), node.path("hollandScores").path(String.valueOf(c)).asDouble());
            var occupation = new Occupation(id, node.path("name").asText(), node.path("family").asText(), axes,
                    node.path("coreSkills"), node.path("salaryRange"), node.path("growthPath").asText(), node.path("source").asText(), .8);
            items.add(new Item(occupation, .8 - items.size() * .01, .8 - items.size() * .01, items.size() + 1, 0, ""));
        }
        return new Revision("parent", null, Constraints.empty(), items, 0, false, "");
    }
    @Test void deterministicScoringAndDeltasExplainTheActualSalaryAndResearchConstraint() throws Exception {
        var parent = parent();
        var constraints = new Constraints("上海", 30, true, true);
        var result = CareerRecommendationService.rescore(parent, constraints, 0, () -> 0);
        var again = CareerRecommendationService.rescore(parent, constraints, 0, () -> 0);
        assertThat(result.items()).isEqualTo(again.items());
        assertThat(result.parentId()).isEqualTo(parent.id());
        assertThat(result.items().get(0).occupation().occupationId()).isEqualTo("occ_backend_dev");
        assertThat(result.items().get(0).rankDelta()).isEqualTo(1);
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.changeReason()).contains("偏研究", "30k", "上海", "无城市覆盖", "无工时数据");
            var old = parent.items().stream().filter(i -> i.occupation().occupationId().equals(item.occupation().occupationId())).findFirst().orElseThrow();
            assertThat(item.rankDelta()).isEqualTo(old.rank() - item.rank());
        });
        // Reapplying constraints uses original scores; bonuses cannot accumulate across revisions.
        var reapplied = CareerRecommendationService.rescore(result, constraints, 0, () -> 0);
        assertThat(reapplied.items()).extracting(Item::score).containsExactlyElementsOf(result.items().stream().map(Item::score).toList());
        assertThat(reapplied.items()).allSatisfy(item -> assertThat(item.rankDelta()).isZero());
        var removed = CareerRecommendationService.rescore(result, Constraints.empty(), 0, () -> 0);
        assertThat(removed.items()).allSatisfy(item -> {
            assertThat(item.score()).isEqualTo(item.baseScore());
            assertThat(item.changeReason()).contains("替换上一轮约束");
        });
    }
    @Test void unsupportedConstraintsPreserveOriginalHybridOrder() throws Exception {
        var parent = parent();
        var result = CareerRecommendationService.rescore(parent, new Constraints("杭州", null, true, false), 0, () -> 0);
        assertThat(result.items()).extracting(i -> i.occupation().occupationId())
                .isEqualTo(parent.items().stream().map(i -> i.occupation().occupationId()).toList());
        assertThat(result.items()).allSatisfy(i -> assertThat(i.rankDelta()).isZero());
    }
    @Test void realCatalogMissingSalaryCannotAffectRankingOrScores() throws Exception {
        var parent=parent();
        assertThat(parent.items()).allSatisfy(i -> assertThat(i.occupation().salaryRange().isNull()).isTrue());
        var baseline=CareerRecommendationService.rescore(parent,Constraints.empty(),0,() -> 0);
        var constrained=CareerRecommendationService.rescore(parent,new Constraints("",30,false,false),0,() -> 0);
        assertThat(constrained.items()).extracting(Item::score).isEqualTo(baseline.items().stream().map(Item::score).toList());
        assertThat(constrained.items()).extracting(i -> i.occupation().occupationId()).isEqualTo(baseline.items().stream().map(i -> i.occupation().occupationId()).toList());
    }
    @Test void explanationTimeoutFallsBackWithoutLosingScoresAndMissingFactsNeverBecomeScores() throws Exception {
        var parent = parent();
        var constraints = new Constraints("杭州", 30, true, true);
        var normal = CareerRecommendationService.rescore(parent, constraints, 0, () -> 0);
        var fallback = CareerRecommendationService.rescore(parent, constraints, 0, () -> 1_500_000_000L);
        assertThat(fallback.explanationFallback()).isTrue();
        assertThat(fallback.items()).extracting(Item::score).isEqualTo(normal.items().stream().map(Item::score).toList());
        assertThat(fallback.items()).allSatisfy(i -> assertThat(i.changeReason()).contains("无可比薪资，保留基础分"));
        var unknownSalary = parent.items().get(0).occupation();
        var unknown = new Occupation(unknownSalary.occupationId(), unknownSalary.name(), unknownSalary.family(), unknownSalary.riasec(),
                unknownSalary.coreSkills(), json.nullNode(), null, unknownSalary.source(), .8);
        var noFacts = new Revision("parent", null, Constraints.empty(), List.of(new Item(unknown, .8, .8, 1, 0, "")), 0, false, "");
        var result = CareerRecommendationService.rescore(noFacts, new Constraints("杭州", 30, true, false), 0, () -> 0);
        assertThat(result.items().get(0).score()).isEqualTo(.8);
        assertThat(result.items().get(0).changeReason()).contains("无可比薪资", "待核实");
    }
}
