package com.offerpilot.memory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class AutomaticMemoryExtractorTest {
    @SuppressWarnings("unchecked") final ObjectProvider<ModelGateway> providers=mock(ObjectProvider.class);
    final ModelGateway gateway=mock(ModelGateway.class);
    final AutomaticMemoryExtractor extractor=new AutomaticMemoryExtractor(providers,new ObjectMapper());
    @Test void offlineSelectsSeveralExactStatementsAndDeduplicatesWithoutInferring() {
        var facts=extractor.extract("alice","我的目标城市是杭州。我的偏好是远程办公。我的目标城市是杭州。");
        assertThat(facts).extracting(AutomaticMemoryExtractor.Fact::fact).containsExactly("我的目标城市是杭州","我的偏好是远程办公");
        assertThat(facts).allSatisfy(f -> { assertThat(f.confidence()).isBetween(0.0,1.0); assertThat(f.extractor()).isEqualTo("rule"); });
    }
    @Test void rejectsDocumentsQuestionsHypothesesThirdPartyAndNegatedGoals() {
        for(String text:List.of("以下简历：我的目标城市是杭州","我的目标城市是杭州？","如果我的目标城市是杭州","他希望做产品经理", "我不想做程序员", "我以前熟悉Java", "我希望做程序员但不确定"))
            assertThat(extractor.extract("alice",text)).as(text).isEmpty();
        verifyNoInteractions(gateway);
    }
    @Test void flashClaimsMustBeExactGroundedSpansWithValidConfidenceAndFallbackOnFailure() {
        when(providers.getIfAvailable()).thenReturn(gateway);
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(reply("[{\"fact\":\"我的目标城市是杭州\",\"confidence\":0.91},{\"fact\":\"我的目标城市是北京\",\"confidence\":1}]"));
        assertThat(extractor.extract("alice","我的目标城市是杭州")).singleElement().satisfies(f -> {
            assertThat(f.fact()).isEqualTo("我的目标城市是杭州");assertThat(f.extractor()).isEqualTo("model");assertThat(f.confidence()).isEqualTo(.91);
        });
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(reply("[{\"fact\":\"编造\",\"confidence\":8}]"));
        assertThat(extractor.extract("alice","我的目标城市是杭州")).singleElement().satisfies(f -> assertThat(f.extractor()).isEqualTo("rule"));
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(Flux.error(new IllegalStateException("stub unavailable")));
        assertThat(extractor.extract("alice","我的目标城市是杭州")).hasSize(1);
    }
    static Flux<ChatResponse> reply(String text) { return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))); }
}
