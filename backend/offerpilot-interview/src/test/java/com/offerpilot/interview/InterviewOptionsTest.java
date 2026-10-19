package com.offerpilot.interview;
import com.offerpilot.interview.application.InterviewOptions;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class InterviewOptionsTest {
    @Test void optionsChangeMixDepthResumeContextAndTimeBudget() {
        var easy = new InterviewOptions("r", "v", "EASY", "TEXT", 15);
        var hard = new InterviewOptions(null, null, "HARD", "VOICE", 60);
        easy.validate(); hard.validate();
        assertThat(easy.questions("后端", "技术", "订单项目")).hasSize(3).allMatch(q -> q.contains("基础概念") && q.contains("订单项目"));
        assertThat(hard.questions("后端", "技术", null)).hasSize(10).allMatch(q -> q.contains("失败边界"));
        assertThat(easy.questions("后端", "行为", null).get(0)).contains("协作冲突");
        assertThat(easy.questions("后端", "HR", null).get(0)).contains("职业方向");
        assertThat(new InterviewOptions(null,null,"STANDARD","TEXT",30).questions("后端","综合",null))
                .hasSize(5).anyMatch(q -> q.contains("技术")).anyMatch(q -> q.contains("协作冲突")).anyMatch(q -> q.contains("职业方向"));
        assertThat(new InterviewOptions(null,null,"STANDARD","TEXT",45).questionCount()).isEqualTo(8);
    }
    @Test void rejectsInvalidOptionsAndHalfSpecifiedResume() {
        for (var options : List.of(new InterviewOptions("r", null,"EASY","TEXT",15),
                new InterviewOptions(null,"v","EASY","TEXT",15), new InterviewOptions(null,null,"BAD","TEXT",15),
                new InterviewOptions(null,null,"EASY","VIDEO",15), new InterviewOptions(null,null,"EASY","TEXT",20),
                new InterviewOptions(" ","v","EASY","TEXT",30)))
            assertThatThrownBy(options::validate).isInstanceOf(BusinessException.class);
    }
}
