package com.offerpilot.llm;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageJournalTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    MockEnvironment env=new MockEnvironment().withProperty("offerpilot.llm.pricing.flash.input-per-million","2")
            .withProperty("offerpilot.llm.pricing.flash.output-per-million","8");
    UsageJournal journal=new UsageJournal(jdbc,env);
    UsageJournal.Scope scope=new UsageJournal.Scope("owner","run","message");
    ChatResponse response(int input,int output) {
        return new ChatResponse(List.of(),ChatResponseMetadata.builder().usage(new DefaultUsage(input,output)).build());
    }
    @Test void configuredPricesUseInputAndOutputSeparatelyAndMissingPriceStaysUnknown() {
        assertThat(journal.cost(ModelTier.FLASH,1000,500,true)).isEqualByComparingTo("0.006");
        assertThat(journal.cost(ModelTier.PRO,1000,500,true)).isNull();
        assertThat(journal.cost(ModelTier.FLASH,1000,500,false)).isNull();
        env.setProperty("offerpilot.llm.pricing.flash.input-per-million","-1");
        assertThat(journal.cost(ModelTier.FLASH,1000,500,true)).isNull();
    }
    @Test void cumulativeMetadataIsStoredOnceBeforeCompletionWithAuthenticatedCorrelation() {
        journal.observe(scope,ModelTier.FLASH,"actual-model",Flux.just(response(10,2),response(10,2),response(10,5))).blockLast();
        verify(jdbc).update(startsWith("INSERT INTO llm_usage_logs"),anyString(),eq("owner"),eq("run"),eq("message"),eq("actual-model"),
                eq(10),eq(5),anyLong(),eq(new BigDecimal("0.0000600000")),eq(true),eq("COMPLETED"));
        verifyNoMoreInteractions(jdbc);
    }
    @Test void errorAndCancelPersistPartialUsageAndNeverInventATokenEstimate() {
        StepVerifier.create(journal.observe(scope,ModelTier.FLASH,"model",Flux.concat(Flux.just(response(4,1)),Flux.error(new IllegalStateException("offline")))))
                .expectNextCount(1).expectErrorMessage("offline").verify();
        journal.observe(scope,ModelTier.FLASH,"model",Flux.never()).subscribe().dispose();
        verify(jdbc).update(anyString(),anyString(),eq("owner"),eq("run"),eq("message"),eq("model"),eq(4),eq(1),anyLong(),any(),eq(true),eq("FAILED"));
        verify(jdbc).update(anyString(),anyString(),eq("owner"),eq("run"),eq("message"),eq("model"),eq(0),eq(0),anyLong(),isNull(),eq(false),eq("CANCELLED"));
    }
    @Test void blockingNestedCallsReceiveOnlyTheirReactorScopeAndRestoreThreadLocal() {
        assertThat(UsageJournal.current()).isNull();
        var actual=UsageJournal.callable(UsageJournal::current).contextWrite(c->c.put(UsageJournal.KEY,scope)).block();
        assertThat(actual).isEqualTo(scope);assertThat(UsageJournal.current()).isNull();
    }
}
