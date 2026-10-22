package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.application.RunRelay;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Flyway V48/JDBC/owner HTTP boundary; model events are an explicit test fixture, no paid IO. */
@AutoConfigureMockMvc
@TestPropertySource(properties={"offerpilot.llm.pricing.flash.input-per-million=2", "offerpilot.llm.pricing.flash.output-per-million=8"})
class AgentConversationUxIntegrationTest extends SharedHttpIntegrationTest {
    @Autowired GuestIdentityService guests;
    @Autowired ConversationService conversations;
    @Autowired RunRelay relay;
    @Autowired UsageJournal journal;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Test void durableEventReplayAndMessageUsageSurviveRelayExpiryRespectOwnershipAndCascade() throws Exception {
        String token=guests.create().accessToken(),other=guests.create().accessToken(),owner=guests.require("Bearer "+token);
        var turn=conversations.begin(owner,null,"fixture",AgentMode.COACH,"actual-model");
        var response=new ChatResponse(List.of(),ChatResponseMetadata.builder().usage(new DefaultUsage(1000,500)).build());
        journal.observe(new UsageJournal.Scope(owner,turn.runId(),turn.messageId()),ModelTier.FLASH,"actual-model",Flux.just(response,response)).blockLast();
        var summary=journal.summary(owner,turn.messageId());
        assertThat(summary.promptTokens()).isEqualTo(1000);assertThat(summary.costEstimate()).isEqualTo(0.006);
        assertThat(journal.summary(guests.require("Bearer "+other),turn.messageId())).isNull();
        var events=List.<AgentEvent>of(
                new AgentEvent.RunStarted("",1,turn.runId(),turn.startedAt(),turn.conversationId(),turn.messageId(),"actual-model",AgentMode.COACH),
                new AgentEvent.Citation("",2,turn.runId(),Instant.now(),null,List.of(new AgentEvent.CitationSource("d","来源","真实摘录",null,"https://example.test","fixture",1))),
                new AgentEvent.TextDelta("",3,turn.runId(),Instant.now(),null,"真实回复[1]"),
                new AgentEvent.UiCard("",4,turn.runId(),Instant.now(),null,"radar","skill_radar",Map.of("axes",List.of())),
                new AgentEvent.Usage("",5,turn.runId(),Instant.now(),summary.model(),summary.promptTokens(),summary.completionTokens(),summary.costEstimate(),false,summary.durationMs()),
                new AgentEvent.RunCompleted("",6,turn.runId(),Instant.now(),turn.messageId(),"COMPLETED",100));
        assertThat(relay.start(owner,Flux.fromIterable(events)).collectList().block(Duration.ofSeconds(5))).containsExactlyElementsOf(events);
        conversations.finish(turn,"真实回复[1]","COMPLETED",null,null,List.of());
        // Fresh relay has no memory buffer: durable API still returns every original event in order.
        var restarted=new RunRelay(Duration.ofSeconds(1),Duration.ofSeconds(1),
                new com.offerpilot.agent.application.WorkbenchEvents(jdbc,json,conversations));
        assertThat(restarted.history(owner,turn.runId())).extracting(AgentEvent::type)
                .containsExactly("run.started","citation","text.delta","ui.card","usage","run.completed");
        assertThat(restarted.history(owner,turn.runId())).extracting(AgentEvent::id).containsExactly(1,2,3,4,5,6);
        mvc.perform(get("/api/v1/agent/runs/{id}/workbench",turn.runId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/agent/runs/{id}/workbench",turn.runId()).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agent/runs/{id}/workbench",turn.runId()).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[2].text").value("真实回复[1]"));
        var message=conversations.messages(owner,turn.conversationId(),null,50).items().stream().filter(m->m.id().equals(turn.messageId())).findFirst().orElseThrow();
        assertThat(message.usage().path("model").asText()).isEqualTo("actual-model");
        assertThat(message.usage().path("costEstimate").asDouble()).isEqualTo(0.006);
        assertThat(message.parts().toString()).contains("真实摘录");
        jdbc.update("DELETE FROM users WHERE id=?",owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage_logs WHERE message_id=?",Integer.class,turn.messageId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_events WHERE run_id=?",Integer.class,turn.runId())).isZero();
    }
}
