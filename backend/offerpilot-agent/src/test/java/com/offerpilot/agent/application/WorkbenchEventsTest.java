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
    @Test void completionMarkerIsReadBeforeRowsSoAConcurrentFinalAppendCannotBeSkipped() {
        var jdbc=mock(JdbcTemplate.class);
        var conversations=mock(ConversationService.class);
        var history=new WorkbenchEvents(jdbc,new ObjectMapper(),conversations);
        var markerRead=new java.util.concurrent.atomic.AtomicBoolean();
        var last=new AgentEvent.TextDelta("text.delta",9,"run",Instant.now(),null,"最后一个事件");
        when(jdbc.queryForObject(contains("NOT events_finished"),eq(Boolean.class),eq("run"),eq("alice")))
                .thenAnswer(c -> { markerRead.set(true); return false; });
        when(jdbc.query(startsWith("SELECT payload FROM agent_events"),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<AgentEvent>>any(),eq("run"),eq(8)))
                .thenAnswer(c -> markerRead.get() ? java.util.List.of(last) : java.util.List.of());
        var batch=history.tail("alice","run",8);
        assertThat(batch.active()).isFalse();
        assertThat(batch.events()).containsExactly(last);
        var order=inOrder(conversations,jdbc);
        order.verify(conversations).requireRunOwner("alice","run");
        order.verify(jdbc).update(contains("reconnect_until"),eq("run"),eq("alice"));
        order.verify(jdbc).queryForObject(contains("NOT events_finished"),eq(Boolean.class),eq("run"),eq("alice"));
        order.verify(jdbc).query(contains("seq>? ORDER BY seq LIMIT 500"),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<AgentEvent>>any(),eq("run"),eq(8));
    }
    @Test void ownerIsCheckedBeforeReadingAnyEvent() {
        var jdbc=mock(JdbcTemplate.class);var conversations=mock(ConversationService.class);
        doThrow(new IllegalStateException("forbidden")).when(conversations).requireRunOwner("other","r");
        assertThatThrownBy(()->new WorkbenchEvents(jdbc,new ObjectMapper(),conversations).read("other","r")).hasMessage("forbidden");
        verifyNoInteractions(jdbc);
    }
}
