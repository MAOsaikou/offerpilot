package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CareerAssessmentInferenceTest {
    @SuppressWarnings("unchecked")
    final CareerAssessmentInference inference = assertDoesNotThrow();
    final List<CareerAssessmentInference.QA> answers = List.of(
            new CareerAssessmentInference.QA("课余", "我喜欢研究算法题，经常为了弄懂原理熬到很晚。"),
            new CareerAssessmentInference.QA("成就", "我组织了社团的编程比赛，带领十个人完成报名和赛题。"),
            new CareerAssessmentInference.QA("条件", "想去杭州，月薪 12k 左右，偶尔加班可以。"));

    private static CareerAssessmentInference assertDoesNotThrow() {
        try {
            return new CareerAssessmentInference(mock(ObjectProvider.class), new ObjectMapper(), new ByteArrayResource(new byte[0]));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test void modelClaimsWithoutVerbatimEvidenceAreDropped() {
        String output = """
                {"riasec":{"R":10,"I":85,"A":20,"S":30,"E":70,"C":25},
                 "riasecEvidence":{"I":"为了弄懂原理熬到很晚","E":"我是天生的领导者"},
                 "traits":[{"name":"好奇钻研","confidence":0.8,"evidence":"经常为了弄懂原理熬到很晚"},
                           {"name":"主动推动","confidence":0.7,"evidence":"带领十个人完成报名和赛题"},
                           {"name":"抗压","confidence":0.9,"evidence":"我在压力下表现最好"},
                           {"name":"有规划","confidence":0.5,"evidence":"想去杭州"}],
                 "constraints":{"city":"杭州","salary":"12k","overtime":"偶尔加班"},"summary":"爱钻研也能组织"}
                """;
        var result = inference.validate(output, answers);
        assertThat(result.source()).isEqualTo("model");
        assertThat(result.riasec()).containsEntry("I", 85).containsEntry("E", 70);
        assertThat(result.riasecEvidence()).containsOnlyKeys("I");
        // The fabricated quote is gone; rules top the list up to five grounded traits.
        assertThat(result.traits()).extracting(CareerAssessmentInference.Trait::name).doesNotContain("抗压")
                .startsWith("好奇钻研", "主动推动", "有规划");
        assertThat(result.traits()).allSatisfy(t -> assertThat(CareerAssessmentInference.grounded(t.evidence(), answers)).isTrue());
        assertThat(inference.validate("{\"riasec\":{\"R\":1}}", answers)).isNull();
    }

    @Test void offlineRulesScoreFromKeywordsAndQuoteTheSentence() {
        var result = inference.infer("u1", answers);
        assertThat(result.source()).isEqualTo("rule");
        assertThat(result.riasec().get("I")).isGreaterThan(result.riasec().get("R"));
        assertThat(result.riasec().get("E")).isGreaterThan(result.riasec().get("A"));
        assertThat(result.riasecEvidence().get("I")).isEqualTo("我喜欢研究算法题，经常为了弄懂原理熬到很晚");
        assertThat(result.traits()).extracting(CareerAssessmentInference.Trait::name).contains("好奇钻研", "主动推动");
        assertThat(result.constraints()).isEqualTo(new CareerAssessmentInference.Constraints("杭州", "12k", "偶尔加班"));
    }
}
