package com.offerpilot.llm;

import com.offerpilot.config.OfferPilotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelGatewayUsageJournalTest {
    @Test void reactorAndBlockingScopesReachTheActualProviderObserverWithoutLeakingToOtherCalls() {
        var tier=new OfferPilotProperties.Llm.Tier("deepseek","fixture-model",Duration.ofSeconds(5),false);
        var gateway=new ModelGateway(new OfferPilotProperties(null,null,null,null,null,
                new OfferPilotProperties.Llm("FLASH",Map.of("FLASH",tier),Map.of()),null,null),
                new MockEnvironment().withProperty("spring.ai.deepseek.api-key","fixture-only")
                        .withProperty("spring.ai.deepseek.base-url","https://provider.invalid")
                        .withProperty("offerpilot.llm.injection-classifier-enabled","false"));
        var provider=mock(ChatModel.class);
        when(provider.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of())));
        ReflectionTestUtils.setField(gateway,"models",new java.util.concurrent.ConcurrentHashMap<>(Map.of(ModelTier.FLASH,provider)));
        var journal=mock(UsageJournal.class);
        when(journal.observe(any(),any(),anyString(),any())).thenAnswer(call->call.getArgument(3));
        ReflectionTestUtils.setField(gateway,"usageJournal",journal);
        var first=new UsageJournal.Scope("owner-a","run-a","message-a");
        var second=new UsageJournal.Scope("owner-b","run-b","message-b");
        gateway.stream(ModelTier.FLASH,List.of(new UserMessage("fixture"))).contextWrite(c->c.put(UsageJournal.KEY,first)).blockLast();
        UsageJournal.callable(()->gateway.stream(ModelTier.FLASH,List.of(new UserMessage("fixture"))).blockLast())
                .contextWrite(c->c.put(UsageJournal.KEY,second)).block();
        gateway.stream(ModelTier.FLASH,List.of(new UserMessage("fixture"))).blockLast();
        verify(journal).observe(eq(first),eq(ModelTier.FLASH),eq("fixture-model"),any());
        verify(journal).observe(eq(second),eq(ModelTier.FLASH),eq("fixture-model"),any());
        verifyNoMoreInteractions(journal);
    }
}
