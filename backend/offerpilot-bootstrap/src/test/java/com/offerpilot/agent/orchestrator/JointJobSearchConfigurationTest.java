package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses shipped prompt resources and Spring injection, with no DB, HTTP server or provider. */
class JointJobSearchConfigurationTest {
    @Test void defaultUsesJointPromptAndPropertyFalseRestoresOriginalCalls() {
        for (boolean enabled : List.of(true, false)) {
            var gateway = mock(ModelGateway.class);
            when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(response("""
                    {"kind":"single","expert":"JOB","confidence":1,"needsClarification":false,"ruleRoute":true,
                    "jobSearch":{"action":"search","query":"Java","nature":"intern","location":"上海"}}
                    """), response("{\"action\":\"search\",\"query\":\"Java\",\"nature\":\"intern\",\"location\":\"北京\"}"));
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("control",
                        Map.of("offerpilot.agent.joint-job-search-planning", enabled,
                                "offerpilot.agent.rule-first-routing", false)));
                context.getBeanFactory().registerSingleton("gateway", gateway);
                context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
                context.getBeanFactory().registerSingleton("experts", new ExpertRegistry());
                context.register(IntentPlanner.class, JobIntentParser.class, ResumeIntentParser.class);
                context.refresh();
                var decision = context.getBean(IntentPlanner.class).prepare(AgentMode.AUTO, "找上海Java实习", List.of(),
                        ModelTier.FLASH, ignored -> {}, Map.of()).block(Duration.ofSeconds(2));
                assertThat(decision.ruleRoute()).isFalse(); // Provider JSON cannot grant early IO provenance.
                assertThat(decision.plan().steps().get(0).inputs()).containsEntry("location", enabled ? "上海" : "北京");
                verify(gateway, times(enabled ? 1 : 2)).stream(eq(ModelTier.FLASH), anyList(), eq(0.1));
                verify(gateway).stream(eq(ModelTier.FLASH), argThat(messages -> {
                    String system = messages.get(0).getText();
                    return system.contains("意图识别与规划") && system.contains("jobSearch=") == enabled
                            && !system.contains("{experts}");
                }), eq(0.1));
            }
        }
    }

    @Test void ruleRouteIsEnabledByDefaultAndSpringPropertyFalseRestoresModelPlanning() {
        for (boolean enabled : List.of(true, false)) {
            var gateway = mock(ModelGateway.class);
            when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(response("{\"kind\":\"chitchat\",\"confidence\":1}"));
            try (var context = new AnnotationConfigApplicationContext()) {
                if (!enabled) context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("control",
                        Map.of("offerpilot.agent.rule-first-routing", false)));
                context.getBeanFactory().registerSingleton("gateway", gateway);
                context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
                context.getBeanFactory().registerSingleton("experts", new ExpertRegistry());
                context.register(IntentPlanner.class, JobIntentParser.class, ResumeIntentParser.class);
                context.refresh();
                var decision = context.getBean(IntentPlanner.class).prepare(AgentMode.AUTO, "找上海Java实习", List.of(),
                        ModelTier.FLASH, ignored -> {}, Map.of()).block(Duration.ofSeconds(2));
                if (enabled) {
                    assertThat(decision.ruleRoute()).isTrue();
                    assertThat(decision.plan().steps().get(0).inputs()).containsEntry("nature", "intern");
                    verifyNoInteractions(gateway);
                } else {
                    assertThat(decision.plan()).isNull();
                    verify(gateway).stream(eq(ModelTier.FLASH), anyList(), eq(0.1));
                }
            }
        }
    }

    private Flux<ChatResponse> response(String text) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }
}
