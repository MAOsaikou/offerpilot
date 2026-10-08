package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.job.application.*;
import com.offerpilot.llm.*;
import com.offerpilot.resume.application.ResumeDiagnosisForChat;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobSearchExecutionTest {
    final ModelGateway gateway = mock(ModelGateway.class);
    final JobSearchForChat searches = mock(JobSearchForChat.class);
    final JobMatchForChat matches = mock(JobMatchForChat.class);
    final ObjectMapper json = new ObjectMapper();
    final List<Message> reply = new ArrayList<>();
    final Map<String, Object> context = Map.of("jobId", "job_1", "resumeId", "res_1", "resumeVersionId", "ver_1", "optimizationReportId", "old_optimization");

    Orchestrator orchestrator() throws Exception {
        var beans = new StaticListableBeanFactory();
        beans.addBean("matches", matches);
        var experts = new ExpertRegistry(beans.getBeanProvider(JobMatchForChat.class), beans.getBeanProvider(ResumeDiagnosisForChat.class), searches);
        var parser = new JobIntentParser(gateway, json, new ByteArrayResource("extract".getBytes()));
        return new Orchestrator(new IntentPlanner(gateway, json, experts, new ByteArrayResource("{experts}".getBytes()), parser), experts);
    }

    Flux<ChatResponse> response(String text) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    List<AgentEvent> run(AgentMode mode, Map<String,Object> selected) throws Exception {
        return orchestrator().run(mode, "换成北京的 Java 校招", List.of(new UserMessage("找上海Java校招")), ModelTier.CHEAP,
                "run", new AtomicInteger(), reply, ignored -> {}, "owner", selected).collectList().block(Duration.ofSeconds(5));
    }

    @Test void autoAndExplicitSearchIgnoreStaleMatchIdsAndEmitOneMcpCard() throws Exception {
        String extract = "{\"action\":\"search\",\"query\":\"Java\",\"site\":\"\",\"nature\":\"campus\",\"location\":\"北京\"}";
        when(searches.search("Java", "", "campus", "北京")).thenReturn(json.createObjectNode().put("schemaVersion", 2).put("returnedCount", 0));
        for (var mode : List.of(AgentMode.AUTO, AgentMode.JOB)) {
            if (mode == AgentMode.AUTO) when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(
                    response("{\"kind\":\"single\",\"expert\":\"JOB\",\"confidence\":1}"), response(extract));
            else when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(response(extract));
            var events = run(mode, context);
            assertThat(events).filteredOn(AgentEvent.UiCard.class::isInstance).hasSize(1);
            assertThat(events).filteredOn(AgentEvent.ToolCall.class::isInstance).singleElement().satisfies(event -> {
                var call = (AgentEvent.ToolCall)event;
                assertThat(call.name()).isEqualTo("search_jobs");
                assertThat(call.source()).isEqualTo("mcp:jobs-mcp");
                assertThat(call.args().toString()).contains("北京", "Java");
            });
        }
        verifyNoInteractions(matches);
        verify(gateway, atLeastOnce()).stream(any(), argThat(messages -> messages.stream().anyMatch(m -> m.getText().contains("找上海Java校招"))), eq(0.1));
    }

    @Test void missingKeywordsInvalidFieldsAndMissingMatchSelectionClarifyWithoutTools() throws Exception {
        for (String extract : List.of("{\"action\":\"search\",\"query\":\"\",\"nature\":\"all\"}",
                "{\"action\":\"search\",\"query\":\"Java\",\"nature\":\"invalid\"}",
                "{\"action\":\"match\",\"jobId\":\"guessed\"}", "{\"action\":\"match\"}",
                "{\"action\":\"clarify\",\"question\":\"想找什么方向？\"}", "null",
                "{\"action\":\"search\",\"query\":123,\"nature\":\"all\"}")) {
            when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(response(extract));
            assertThat(run(AgentMode.JOB, Map.of())).isEmpty();
        }
        verifyNoInteractions(searches, matches);
        assertThat(reply).allSatisfy(message -> assertThat(message.getText()).contains("澄清"));
    }

    @Test void upstreamFailureIsFailedSearchNotAnEmptyResultOrPlaceholder() throws Exception {
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(response("{\"action\":\"search\",\"query\":\"Java\",\"nature\":\"all\"}"));
        when(searches.search(anyString(), anyString(), anyString(), anyString())).thenThrow(new RuntimeException("private-secret"));
        var events = run(AgentMode.JOB, Map.of());
        assertThat(events).noneMatch(AgentEvent.UiCard.class::isInstance);
        assertThat(events.toString()).contains("岗位搜索失败或超时").doesNotContain("private-secret", "placeholder");
        assertThat(reply.get(0).getText()).contains("不要编造岗位");
    }

    @Test void cancellingParameterExtractionDoesNotCallTools() throws Exception {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        var subscription = orchestrator().run(AgentMode.JOB, "找Java", List.of(), ModelTier.CHEAP, "run", new AtomicInteger(),
                reply, ignored -> {}, "owner", context).subscribe();
        subscription.dispose();
        assertThat(cancelled).isTrue();
        verifyNoInteractions(searches, matches);
    }

    @Test void matchingUsesCallerSelectionAndDiscardsResourceIdsFromTheModelPlan() throws Exception {
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(response("""
                {"kind":"composite","confidence":1,"plan":{"goal":"匹配","steps":[
                {"id":"s1","title":"匹配岗位","agent":"JOB","inputs":{"jobId":"guessed-job","resumeId":"guessed-resume","resumeVersionId":"guessed-version"},"dependsOn":[]}]}}
                """), response("{\"action\":\"match\"}"));
        var report = json.createObjectNode().put("id", "match_selected").put("score", 80).put("summary", "匹配完成");
        report.putArray("requirements");
        when(matches.match(eq("owner"), eq("job_1"), eq("res_1"), eq("ver_1"), anyString()))
                .thenReturn(JobMatchCards.fromReport(report));
        var events = run(AgentMode.AUTO, context);
        verify(matches).match(eq("owner"), eq("job_1"), eq("res_1"), eq("ver_1"), anyString());
        verifyNoInteractions(searches);
        assertThat(events).filteredOn(AgentEvent.UiCard.class::isInstance).singleElement();
        assertThat(events.toString()).doesNotContain("guessed-job", "guessed-resume", "guessed-version");
    }
}
