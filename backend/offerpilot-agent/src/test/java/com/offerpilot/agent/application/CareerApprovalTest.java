package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.orchestrator.IntentPlanner;
import com.offerpilot.agent.orchestrator.Orchestrator;
import com.offerpilot.career.application.*;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CareerApprovalTest {
    final ConversationService conversations = mock(ConversationService.class);
    final CareerProfileService profiles = mock(CareerProfileService.class);
    final OccupationRecommender occupations = mock(OccupationRecommender.class);
    final CareerRecommendForChat careers = new DefaultCareerRecommendForChat(profiles, occupations);
    final ApprovalService approvals = new ApprovalService(conversations);
    final CareerWriteTools tools = new CareerWriteTools(careers, approvals);
    final ModelGateway gateway = mock(ModelGateway.class);

    ExpertRegistry registry() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("careers", careers);
        beans.addBean("careerWrites", tools);
        return new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),
                beans.getBeanProvider(com.offerpilot.resume.application.ResumeDiagnosisForChat.class), null, null, null, null,
                beans.getBeanProvider(CareerRecommendForChat.class), beans.getBeanProvider(CareerWriteTools.class));
    }
    IntentPlanner planner(ExpertRegistry registry) throws Exception {
        return new IntentPlanner(gateway, new ObjectMapper(), registry, new ByteArrayResource("{experts}".getBytes()));
    }
    PlanStep write() { return new PlanStep("s1", "设置目标", ExpertType.CAREER_DISCOVERY,
            Map.of("action", "setCareerTarget", "occupationId", "occ1", "owner", "attacker"), List.of()); }
    void occupation() {
        when(occupations.find("occ1", null)).thenReturn(new OccupationRecommender.Ranked("occ1", "工程师", "技术", "IRC", 0));
        when(occupations.find("occ1", "工程师")).thenReturn(new OccupationRecommender.Ranked("occ1", "工程师", "技术", "IRC", 0));
        when(profiles.saveTarget("alice", "工程师")).thenReturn(new CareerProfileService.Profile("工程师", "上海", "Java", List.of("技术研发"), List.of(), null));
    }


    @Test void timeoutRejectsWithoutWritingAndUnknownOccupationNeverRequestsApproval() {
        var ticket = approvals.request("alice", "run", "setCareerTarget", Map.of("occupationId", "occ1"), Duration.ofMillis(5));
        var decision = ticket.await().block(Duration.ofSeconds(2));
        assertThat(decision.decision()).isEqualTo("TIMEOUT");
        assertThatThrownBy(() -> tools.execute("alice", write(), decision).block()).hasMessageContaining("超时");
        when(occupations.find("occ1", null)).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "不存在"));
        assertThatThrownBy(() -> tools.request("alice", "run2", write())).hasMessageContaining("不存在");
        verifyNoInteractions(profiles, conversations);
    }

    @Test void cancelledWaitCannotBeApprovedLaterOrWrite() {
        var ticket = approvals.request("alice", "run", "setCareerTarget", Map.of("occupationId", "occ1"));
        var waiting = ticket.await().subscribe();
        waiting.dispose();
        assertThatThrownBy(() -> approvals.resolve("alice", "run", ticket.id(),
                new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(profiles, occupations);
    }

}
