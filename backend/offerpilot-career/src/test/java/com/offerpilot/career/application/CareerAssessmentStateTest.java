package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CareerAssessmentStateTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ObjectMapper json=new ObjectMapper();
    final CareerProfileService profiles=mock(CareerProfileService.class);
    final CareerAssessmentService service=spy(new CareerAssessmentService(jdbc,json,mock(CareerAssessmentInference.class),profiles));
    CareerAssessmentStateTest() {
        when(jdbc.queryForList(contains("FROM users"),eq(String.class),eq("alice"))).thenReturn(List.of("alice"));
        when(jdbc.queryForObject(contains("FOR UPDATE"),eq(String.class),eq("alice"))).thenReturn("alice");
    }
    @Test void questionnaireKeepsConfirmedTraitEvidenceInsteadOfNewUnconfirmedInferenceAndAllowsUndifferentiatedScores() throws Exception {
        var old=new CareerAssessmentInference.Trait("细致",.8,"此前确认的依据");
        var constraints=new CareerAssessmentInference.Constraints("杭州","12k","双休");
        var scores=Map.of("R",50,"I",50,"A",50,"S",50,"E",50,"C",50);
        var prior=new CareerAssessmentService.Confirmed(Map.of("R",10,"I",90,"A",20,"S",40,"E",60,"C",30),"IEC",List.of(old),constraints);
        var unconfirmed=new CareerAssessmentInference.Inference(scores,Map.of(),List.of(new CareerAssessmentInference.Trait("细致",.5,"尚未确认的新依据")),constraints,"","rule");
        doReturn(new CareerAssessmentService.Assessment(CareerAssessmentService.QUESTIONS,Collections.nCopies(7,""),unconfirmed,prior,"before")).when(service).get("alice");
        service.confirmQuestionnaire("alice",scores);
        var value=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(eq("UPDATE career_assessments SET confirmed=?::jsonb, confirmed_at=now() WHERE user_id=?"),value.capture(),eq("alice"));
        var saved=json.readTree(value.getValue());
        assertThat(saved.path("code").asText()).isEmpty();
        assertThat(saved.path("traits").get(0).path("evidence").asText()).isEqualTo(old.evidence());
        assertThat(saved.path("constraints")).isEqualTo(json.valueToTree(constraints));
        verify(profiles).saveAssessmentCity("alice","杭州");
    }
    @Test void lateClarificationDoesNotAttachToAnEditedAnswerOrExceedTwelveTurns() {
        var answers=new ArrayList<>(Collections.nCopies(7,""));answers.set(0,"新的完整回答");
        var current=new CareerAssessmentService.Assessment(CareerAssessmentService.QUESTIONS,answers,null,null,null);
        doReturn(current).when(service).get("alice");
        when(jdbc.queryForObject(startsWith("SELECT followups"),eq(String.class),eq("alice"))).thenReturn("[]");
        assertThat(service.addFollowup("alice",0,"旧回答","能举个例子吗？")).isEqualTo(current);
        verify(jdbc,never()).update(startsWith("UPDATE career_assessments SET followups"),any(),any());
        var twelve=new CareerAssessmentService.Assessment(Collections.nCopies(12,"问题？"),Collections.nCopies(12,"不知道"),null,null,null);
        doReturn(twelve).when(service).get("alice");
        assertThat(service.addFollowup("alice",0,"不知道","再问一个？")).isEqualTo(twelve);
        verify(jdbc,never()).update(startsWith("UPDATE career_assessments SET followups"),any(),any());
    }

    @org.junit.jupiter.api.Test void everyBaseQuestionAsksExactlyOneQuestion() {
        for (String q : CareerAssessmentService.QUESTIONS) {
            long marks = q.chars().filter(c -> c == '？' || c == '?').count();
            org.assertj.core.api.Assertions.assertThat(marks).as(q).isEqualTo(1);
        }
    }
}
