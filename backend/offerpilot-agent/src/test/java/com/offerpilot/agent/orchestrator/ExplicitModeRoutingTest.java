package com.offerpilot.agent.orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.llm.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ExplicitModeRoutingTest {
    ModelGateway gateway=mock(ModelGateway.class);
    IntentPlanner planner() throws Exception { return new IntentPlanner(gateway,new ObjectMapper(),new ExpertRegistry(),new ByteArrayResource("plan".getBytes())); }
    @ParameterizedTest @EnumSource(value=AgentMode.class,names="AUTO",mode=EnumSource.Mode.EXCLUDE)
    void explicitModeNeverCallsAutoRouterOrChangesExpertBecauseOfOtherIntent(AgentMode mode) throws Exception {
        var result=planner().prepare(mode,"帮我搜索岗位并讨论面试规划",List.of(),ModelTier.FLASH,u->{},Map.of()).block();
        assertThat(result.expert()).isEqualTo(ExpertType.valueOf(mode.name()));
        assertThat(result.plan().steps()).singleElement().satisfies(s->assertThat(s.agent()).isEqualTo(ExpertType.valueOf(mode.name())));
        verifyNoInteractions(gateway);
    }
    @Test void autoStillUsesPlanner() throws Exception {
        when(gateway.stream(any(),anyList(),eq(0.1))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"kind\":\"single\",\"expert\":\"JOB\",\"confidence\":1,\"needsClarification\":false}"))))));
        assertThat(planner().prepare(AgentMode.AUTO,"请查岗位",List.of(),ModelTier.FLASH,u->{},Map.of()).block().expert()).isEqualTo(ExpertType.JOB);
        verify(gateway).stream(any(),anyList(),eq(0.1));
    }
}
