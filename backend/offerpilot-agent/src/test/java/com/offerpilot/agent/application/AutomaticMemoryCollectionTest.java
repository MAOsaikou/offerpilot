package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.memory.application.AutomaticMemoryExtractor;
import com.offerpilot.memory.application.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomaticMemoryCollectionTest {
    final UserMemoryService memories=mock(UserMemoryService.class);
    final AutomaticMemoryExtractor extractor=mock(AutomaticMemoryExtractor.class);
    final MemoryChat chat=new MemoryChat(memories,mock(ApprovalService.class),new ObjectMapper());
    AutomaticMemoryCollectionTest() { ReflectionTestUtils.setField(chat,"automatic",extractor); }
    @Test void collectsPersistedUserMessageOnlyAndOrdinaryFactsDoNotBlockOnChatApproval() {
        when(memories.enabled("alice")).thenReturn(true);
        var source=new UserMemoryService.MessageSource("conversation","user-message","我喜欢研究算法");
        var facts=List.of(new AutomaticMemoryExtractor.Fact(UserMemoryService.Kind.PREFERENCE,source.text(),.75,"rule"));
        when(memories.extractionSource("alice","run")).thenReturn(source);
        var scope=new com.offerpilot.llm.UsageJournal.Scope("alice","run","reply-message");
        when(extractor.extract("alice",source.text())).thenAnswer(c -> {
            assertThat(com.offerpilot.llm.UsageJournal.current()).isEqualTo(scope);return facts;
        });
        chat.collect("alice","run").contextWrite(c -> c.put(com.offerpilot.llm.UsageJournal.KEY,scope)).blockLast(Duration.ofSeconds(2));
        assertThat(com.offerpilot.llm.UsageJournal.current()).isNull();
        verify(memories).suggest("alice","run",source,facts);
        assertThat(chat.candidate("alice","我的目标城市是杭州")).isNull();
        assertThat(chat.candidate("alice","记住：我的目标城市是杭州")).isNotNull();
    }
    @Test void cancelDuringExtractionSuppressesLateCandidateWrite() {
        when(memories.enabled("alice")).thenReturn(true);
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        var source=new UserMemoryService.MessageSource("c","m","我喜欢研究算法");
        when(memories.extractionSource("alice","run")).thenReturn(source);
        when(extractor.extract("alice",source.text())).thenAnswer(c -> { active.set(false); return List.of(); });
        chat.collect("alice","run",active::get).blockLast(Duration.ofSeconds(2));
        verify(memories,never()).suggest(anyString(),anyString(),any(),anyList());
    }
    @Test void memoryOffSkipsBothExtractionAndCandidateWriteAndFailureDoesNotFailChat() {
        when(memories.enabled("alice")).thenReturn(false);
        chat.collect("alice","run").blockLast(Duration.ofSeconds(2));
        verifyNoInteractions(extractor);
        verify(memories,never()).extractionSource(anyString(),anyString());
        when(memories.enabled("alice")).thenReturn(true);
        when(memories.extractionSource("alice","run")).thenThrow(new IllegalStateException("offline"));
        assertThatCode(() -> chat.collect("alice","run").blockLast(Duration.ofSeconds(2))).doesNotThrowAnyException();
    }
}
