package com.offerpilot.interview;

import com.offerpilot.interview.application.InterviewRubric;
import com.offerpilot.interview.application.StarStoryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StarStoryServiceTest {
    @Test
    void titlePrefersBodyAndStaysBounded() {
        String title = StarStoryService.titleOf("很长的问题标题".repeat(5), "当时在项目中负责接口改造，我通过拆分模块完成上线，结果延迟降低 30%。");
        assertThat(title.codePointCount(0, title.length())).isLessThanOrEqualTo(48);
        assertThat(title).contains("当时");
    }

    @Test
    void normalizeCollapsesWhitespace() {
        assertThat(StarStoryService.normalize("  A\nB  ")).isEqualTo("a b");
    }

    @Test
    void strongStarAnswerMeetsBankThreshold() {
        String answer = "当时在电商项目中，我负责库存一致性。目标是消除超卖。我通过引入分布式锁和补偿任务排查脏写，最终超卖降为 0，并完成复盘验证。";
        assertThat(InterviewRubric.detect(answer).count()).isGreaterThanOrEqualTo(StarStoryService.MIN_STAR);
    }
}
