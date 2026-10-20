package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.job.application.JobMatchCards;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ByteArrayResource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MatchJobExecutionTest {
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final ObjectMapper json = new ObjectMapper();
    private final List<Message> reply = new ArrayList<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Test
    void proChatMatchSurvivesOldExpertDeadlineAndForwardsTier() throws Exception {
        var facade = (com.offerpilot.job.application.JobMatchForChat) (owner, job, resume, version, request) -> null;
        ExpertRegistry experts = org.mockito.Mockito.spy(new ExpertRegistry(facade));
        var report = json.createObjectNode().put("id", "match_slow").put("score", 80).put("summary", "原文证据");
        report.putArray("requirements");
        var card = JobMatchCards.fromReport(report);
        org.mockito.Mockito.doAnswer(invocation -> reactor.core.publisher.Mono.just(
                com.offerpilot.agent.experts.ExpertResult.card(card.summary(), card.cardId(), card.cardType(), card.payload()))
                .delayElement(Duration.ofSeconds(125))).when(experts).execute(org.mockito.ArgumentMatchers.eq("owner"),
                org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.eq(ModelTier.PRO));
        var approvals=new com.offerpilot.agent.application.ApprovalService(mock(com.offerpilot.conversation.application.ConversationService.class));
        experts.setAnalysisApprovals(approvals);
        var orchestrator = new Orchestrator(new IntentPlanner(gateway, json, experts, new ByteArrayResource("{experts}".getBytes())), experts);
        var context = Map.<String,Object>of("jobAction", "match", "jobId", "job_1", "resumeId", "res_1", "resumeVersionId", "ver_1");
        reactor.test.StepVerifier.withVirtualTime(() -> orchestrator.run(AgentMode.JOB, "匹配", List.of(), ModelTier.PRO,
                "run_slow",new AtomicInteger(),reply,usage -> {},"owner",context).doOnNext(event -> {
                    if(event instanceof AgentEvent.ApprovalRequired a) approvals.resolve("owner","run_slow",a.approvalId(),new com.offerpilot.agent.application.ApprovalService.Decision("APPROVE",null,null));
                }).collectList())
                .expectSubscription().expectNoEvent(Duration.ofSeconds(121)).thenAwait(Duration.ofSeconds(4))
                .assertNext(events -> {
                    assertThat(events).extracting(AgentEvent::type).contains("ui.card").doesNotContain("error");
                    assertThat(events).filteredOn(AgentEvent.ToolResult.class::isInstance).singleElement()
                            .isInstanceOfSatisfying(AgentEvent.ToolResult.class, result -> assertThat(result.ok()).isTrue());
                    assertThat(events).filteredOn(AgentEvent.StepUpdated.class::isInstance)
                            .anySatisfy(event -> assertThat(((AgentEvent.StepUpdated)event).summary()).contains("深度模式较慢"));
                }).verifyComplete();
        org.mockito.Mockito.verify(experts).execute(org.mockito.ArgumentMatchers.eq("owner"),org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq(ModelTier.PRO));
    }

}
