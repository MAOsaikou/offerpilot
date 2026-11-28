package com.offerpilot.conversation.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConversationServiceTitleTest {
    @Test void longQuestionsStayWithinTheRenameLimitAndMarkTheCut() {
        String question = "请结合我已保存的 Java 后端实习目标，用几句话指出我目前最该补的一项证据，不要编造经历。".repeat(3);
        String title = ConversationService.title(question);
        assertThat(title.codePointCount(0, title.length())).isEqualTo(100);
        assertThat(title).endsWith("…");
        assertThat(question).startsWith(title.substring(0, title.length() - 1));
        assertThat(ConversationService.title("短问题")).isEqualTo("短问题");
    }
}
