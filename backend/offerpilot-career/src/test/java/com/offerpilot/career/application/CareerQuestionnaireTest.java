package com.offerpilot.career.application;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CareerQuestionnaireTest {
    @Test void scoresUseAllFiveItemsPerAxisWithZeroToHundredEndpointsAndStableTies() {
        var answers=new LinkedHashMap<String,Integer>();
        CareerQuestionnaire.QUESTIONS.forEach(q -> answers.put(q.id(),q.axis().equals("I")?5:q.axis().equals("A")?4:q.axis().equals("S")?3:1));
        assertThat(CareerQuestionnaire.score(answers)).containsEntry("I",100).containsEntry("A",75).containsEntry("S",50).containsEntry("R",0);
        assertThat(CareerAssessmentService.code(CareerQuestionnaire.score(answers))).isEqualTo("IAS");
        answers.remove("R1");assertThatThrownBy(() -> CareerQuestionnaire.score(answers)).hasMessageContaining("30题");
        answers.put("R1",6);assertThatThrownBy(() -> CareerQuestionnaire.score(answers)).hasMessageContaining("30题");
    }
}
