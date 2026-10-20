package com.offerpilot.agent.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.conversation.application.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class WorkbenchEventsTest {
    @Test void textCardsReasoningAndMemoryAreNowPersistedWithOriginalSequenceForReplay() {
        var jdbc=mock(JdbcTemplate.class);var conversations=mock(ConversationService.class);
        var json=new ObjectMapper().findAndRegisterModules();var history=new WorkbenchEvents(jdbc,json,conversations);
        var now=Instant.now();
        var text=new AgentEvent.TextDelta("",2,"r",now,null,"真实文字");
        var card=new AgentEvent.UiCard("",3,"r",now,null,"c","skill_radar",Map.of("axes",java.util.List.of()));
        history.append(text);history.append(card);
        verify(jdbc).update(anyString(),eq("r"),eq(2),eq("text.delta"),contains("真实文字"));
        verify(jdbc).update(anyString(),eq("r"),eq(3),eq("ui.card"),contains("skill_radar"));
    }
    @Test void ownerIsCheckedBeforeReadingAnyEvent() {
        var jdbc=mock(JdbcTemplate.class);var conversations=mock(ConversationService.class);
        doThrow(new IllegalStateException("forbidden")).when(conversations).requireRunOwner("other","r");
        assertThatThrownBy(()->new WorkbenchEvents(jdbc,new ObjectMapper(),conversations).read("other","r")).hasMessage("forbidden");
        verifyNoInteractions(jdbc);
    }
}
