package com.offerpilot.knowledge.application;

import com.offerpilot.llm.privacy.PiiMaskingPreference;
import com.offerpilot.llm.quota.LlmUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EmbeddingPrivacyTest {
    @Test @SuppressWarnings("unchecked") void privateVectorDocumentsUseTheSameRecognizerAndVerifiedOwnerPreference() {
        var model = mock(EmbeddingModel.class);
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        float[] vector = new float[1024]; vector[0] = 1;
        when(model.embed(anyList())).thenReturn(List.of(vector));
        var env = new MockEnvironment().withProperty("spring.ai.openai.api-key","isolated-key");
        var adapter = new OccupationEmbeddings(provider,env);
        ReflectionTestUtils.setField(adapter,"privacy",(PiiMaskingPreference) owner -> !owner.equals("opted-out"));
        var document = List.of("Redis复盘 13800138000 fictional@example.test 110101199001010015，20% v1.2");
        assertThat(adapter.embed(document)).hasSize(1);
        verify(model).embed(List.of("Redis复盘 [PHONE_1] [EMAIL_1] [ID_CARD_1]，20% v1.2"));
        LlmUserContext.asUser("opted-out",() -> adapter.embed(document));
        verify(model).embed(document);
        assertThat(LlmUserContext.current()).isNull();
    }
}
