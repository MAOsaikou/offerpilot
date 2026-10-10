package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.memory.application.MemoryCandidate;
import com.offerpilot.memory.application.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.offerpilot.memory.application.UserMemoryService.*;

class MemoryChatTest {
    final UserMemoryService memories=mock(UserMemoryService.class);
    final ConversationService conversations=mock(ConversationService.class);
    final ApprovalService approvals=new ApprovalService(conversations);
    final MemoryChat chat=new MemoryChat(memories,approvals,new ObjectMapper());
    final MemoryCandidate.Candidate candidate=new MemoryCandidate.Candidate(Kind.LOCATION,"我的目标城市是杭州",Source.EXPLICIT);
    MemoryChatTest() { when(memories.enabled("alice")).thenReturn(true); }

    @Test void rejectAndCancelledWaitNeverSave() {
        var events=chat.write("alice","run",new AtomicInteger(),candidate).doOnNext(e -> {
            if(e instanceof AgentEvent.ApprovalRequired a) approvals.resolve("alice","run",a.approvalId(),new ApprovalService.Decision("REJECT",null,null));
        }).collectList().block(Duration.ofSeconds(5));
        assertThat(((AgentEvent.ToolResult)events.get(3)).ok()).isFalse();
        String[] id={null};
        var subscription=chat.write("alice","cancelled",new AtomicInteger(),candidate).subscribe(e -> {
            if(e instanceof AgentEvent.ApprovalRequired a) id[0]=a.approvalId();
        });
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> id[0]!=null);
        subscription.dispose();
        assertThatThrownBy(() -> approvals.resolve("alice","cancelled",id[0],new ApprovalService.Decision("APPROVE",null,null)))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        verify(memories,never()).saveApproved(anyString(),anyString(),any());
    }
    @Test void timeoutReturnsFailureWithoutSaving() {
        ApprovalService shortWindow=spy(new ApprovalService(conversations));
        doAnswer(call -> shortWindow.request(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),Duration.ofMillis(20)))
                .when(shortWindow).request(anyString(),anyString(),anyString(),anyMap());
        var events=new MemoryChat(memories,shortWindow,new ObjectMapper()).write("alice","run",new AtomicInteger(),candidate).collectList().block(Duration.ofSeconds(5));
        assertThat(((AgentEvent.ApprovalResolved)events.get(2)).decision()).isEqualTo("TIMEOUT");
        assertThat(((AgentEvent.ToolResult)events.get(3)).ok()).isFalse();
        verify(memories,never()).saveApproved(anyString(),anyString(),any());
    }
    @Test void failedApprovedWriteDoesNotClaimSuccess() {
        when(memories.saveApproved(anyString(),anyString(),any())).thenThrow(new IllegalStateException("internal secret"));
        var events=chat.write("alice","run",new AtomicInteger(),candidate).doOnNext(e -> {
            if(e instanceof AgentEvent.ApprovalRequired a) approvals.resolve("alice","run",a.approvalId(),new ApprovalService.Decision("APPROVE",null,null));
        }).collectList().block(Duration.ofSeconds(5));
        assertThat(((AgentEvent.ToolResult)events.get(3)).ok()).isFalse();
        assertThat(((AgentEvent.ToolResult)events.get(3)).resultPreview()).doesNotContain("internal secret").contains("保存失败");
    }

    @Test void invalidSourcesAndExtraKeysAreRejected() {
        for(Map<String,Object> args:List.of(Map.<String,Object>of("kind","GOAL","fact","fact","source","MANUAL"),
                Map.<String,Object>of("kind","OTHER","fact","x","source","EXPLICIT","owner","bob"),
                Map.<String,Object>of("kind","BAD","fact","x","source","EXPLICIT")))
            assertThatThrownBy(() -> MemoryChat.validate(args)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
    }
}
