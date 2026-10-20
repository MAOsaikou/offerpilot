package com.offerpilot;

import com.offerpilot.agent.application.RunRelay;
import com.offerpilot.agent.application.WorkbenchEvents;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.conversation.application.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL event rows, two relay instances, retention expiry and owner boundary. No model calls. */
class RunRelayPersistenceIntegrationTest extends SharedHttpIntegrationTest {
    @Autowired GuestIdentityService users;
    @Autowired ConversationService conversations;
    @Autowired WorkbenchEvents history;
    @Test void anotherInstanceAndExpiredBufferReplayExactlyAfterLastEventId() {
        String owner=users.require("Bearer "+users.create().accessToken());
        var turn=conversations.begin(owner,null,"测试回放",AgentMode.AUTO,"stub");
        var first=new AgentEvent.RunStarted("run.started",1,turn.runId(),Instant.now(),turn.conversationId(),turn.messageId(),"stub",AgentMode.AUTO);
        var text=new AgentEvent.TextDelta("text.delta",2,turn.runId(),Instant.now(),null,"已保存正文");
        var done=new AgentEvent.RunCompleted("run.completed",3,turn.runId(),Instant.now(),turn.messageId(),"COMPLETED",1);
        var producer=new RunRelay(Duration.ofSeconds(1),Duration.ofMillis(10),history);
        assertThat(producer.start(owner,Flux.just(first,text,done)).collectList().block(Duration.ofSeconds(3))).containsExactly(first,text,done);
        conversations.finish(turn,"已保存正文","COMPLETED",null,null);
        var other=new RunRelay(Duration.ofSeconds(1),Duration.ofSeconds(1),history);
        assertThat(other.resume(owner,turn.runId(),1).collectList().block(Duration.ofSeconds(3))).containsExactly(text,done);
        assertThat(other.resume(owner,turn.runId(),2).collectList().block(Duration.ofSeconds(3))).containsExactly(done);
        assertThat(other.resume(owner,turn.runId(),3).collectList().block(Duration.ofSeconds(3))).isEmpty();
        String stranger=users.require("Bearer "+users.create().accessToken());
        assertThatThrownBy(() -> other.resume(stranger,turn.runId(),0)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        assertThat(history.read(owner,turn.runId())).containsExactly(first,text,done);
    }
}
