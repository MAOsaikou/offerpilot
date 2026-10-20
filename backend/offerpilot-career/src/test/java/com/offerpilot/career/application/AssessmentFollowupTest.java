package com.offerpilot.career.application;

import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssessmentFollowupTest {
    @SuppressWarnings("unchecked") final ObjectProvider<ModelGateway> providers=mock(ObjectProvider.class);
    final ModelGateway model=mock(ModelGateway.class);
    final AssessmentFollowup followup=new AssessmentFollowup(providers);
    @Test void onlyVagueOrShortAnswersTriggerOneQuestionWithOfflineFallback() {
        assertThat(followup.ask("alice","兴趣？","我喜欢编程，最近完成了一个小程序并跟同学一起测试")).isNull();
        assertThat(followup.ask("alice","兴趣？"," ")).isNull();
        assertThat(AssessmentFollowup.valid(followup.ask("alice","兴趣？","不知道"))).isTrue();
        assertThat(AssessmentFollowup.needs("关于未来想做什么，我目前还没想好")).isTrue();
        verifyNoInteractions(model);
    }
    @Test void validFlashQuestionIsUsedAndMultipleQuestionsFallBack() {
        when(providers.getIfAvailable()).thenReturn(model);
        when(model.stream(eq(ModelTier.FLASH),anyList(),eq(.2))).thenReturn(reply("能讲一次你编程时最投入的经历吗？"));
        assertThat(followup.ask("alice","兴趣？","编程")).isEqualTo("能讲一次你编程时最投入的经历吗？");
        when(model.stream(eq(ModelTier.FLASH),anyList(),eq(.2))).thenReturn(reply("为什么？在哪里？"));
        assertThat(followup.ask("alice","兴趣？","编程")).doesNotContain("在哪里");
        when(model.stream(eq(ModelTier.FLASH),anyList(),eq(.2))).thenReturn(Flux.error(new IllegalStateException("stub")));
        assertThat(AssessmentFollowup.valid(followup.ask("alice","兴趣？","编程"))).isTrue();
    }
    static Flux<ChatResponse> reply(String text) { return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))); }
}
