package com.offerpilot.career.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CareerFitScoringTest {
    private final RiasecProfile interest = new RiasecProfile(.45, .75, .2, .2, .2, .4);
    private final CareerFitScoring.Catalog unrelated = new CareerFitScoring.Catalog(
            "design", "Designer", "Art", "A", interest, "Illustration", "Figma", "", "", "Art");
    // Matching evidence must count even if its candidate later falls below MIN_FIT.
    private final CareerFitScoring.Catalog supporting = new CareerFitScoring.Catalog(
            "software", "Developer", "Computing", "E", new RiasecProfile(.55, .25, .8, .8, .8, .6),
            "Software", "Java", "", "", "Computing");

    @Test void unmatchedDimensionsHaveZeroWeightAndExplainWhyTheyAreNotUsed() {
        var context = new CareerFitScoring.Context("原目标", "未知专业", "UnlistedSkill", "", "");
        var candidates = List.of(unrelated, supporting);
        var scores = CareerFitScoring.score(interest, context, candidates);
        assertThat(scores).isEqualTo(CareerFitScoring.score(interest,
                new CareerFitScoring.Context("", "", "", "", ""), candidates));
        assertThat(scores).allSatisfy(score -> {
            assertThat(score.targetWeight()).isZero();
            assertThat(score.majorWeight()).isZero();
            assertThat(score.skillsWeight()).isZero();
            assertThat(score.interestWeight()).isEqualTo(1);
            assertThat(score.total()).isEqualTo(score.interest());
            assertThat(score.explanation()).contains("目标未使用", "专业未使用", "自述技能未使用", "当前候选集无匹配");
        });
    }

    @Test void eachDimensionUsesSharedWeightsOnlyWhenTheCurrentCandidateSetSupportsIt() {
        var contexts = List.of(new CareerFitScoring.Context("Developer", "", "", "", ""),
                new CareerFitScoring.Context("", "Computing", "", "", ""),
                new CareerFitScoring.Context("", "", "Java", "", ""));
        var weights = List.of(.30, .15, .10);
        for (int index = 0; index < contexts.size(); index++) {
            var scores = CareerFitScoring.score(interest, contexts.get(index), List.of(unrelated, supporting));
            var miss = scores.get(0);
            var match = scores.get(1);
            double sum = .45 + weights.get(index);
            assertThat(miss.total()).isCloseTo(.45 / sum, within(1e-12));
            assertThat(match.total()).isCloseTo(weights.get(index) / sum, within(1e-12));
            assertThat(match.total()).isLessThan(CareerFitScoring.MIN_FIT);
            assertThat(miss.targetWeight()).isEqualTo(match.targetWeight());
            assertThat(miss.majorWeight()).isEqualTo(match.majorWeight());
            assertThat(miss.skillsWeight()).isEqualTo(match.skillsWeight());
            // Removing the supporting candidate starts a new request with no evidence for this dimension.
            var next = CareerFitScoring.score(interest, contexts.get(index), List.of(unrelated)).get(0);
            assertThat(next.total()).isEqualTo(1);
            assertThat(next.targetWeight()).isZero();
            assertThat(next.majorWeight()).isZero();
            assertThat(next.skillsWeight()).isZero();
        }
    }
}
