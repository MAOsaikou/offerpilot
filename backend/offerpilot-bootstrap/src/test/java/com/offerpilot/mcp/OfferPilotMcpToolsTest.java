package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.job.application.JobMatcher;
import com.offerpilot.job.application.JobService;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.resume.application.ResumeDiagnoser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfferPilotMcpToolsTest {
    final ResumeDiagnoser diagnoser = mock(ResumeDiagnoser.class);
    final JobMatcher matcher = mock(JobMatcher.class);
    final JobService jobs = mock(JobService.class);
    final ModelGateway gateway = mock(ModelGateway.class);
    final OfferPilotMcpTools tools = new OfferPilotMcpTools(diagnoser, matcher, jobs, gateway, new ObjectMapper());

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsBlankRequiredInputs(String blank) {
        invalid(() -> tools.searchJobs(blank, null, null, null, null), "query");
        invalid(() -> tools.diagnoseResume(blank, null), "resumeText");
        invalid(() -> tools.matchJob(blank, "Java"), "resumeText");
        invalid(() -> tools.matchJob("Java", blank), "jobDescription");
        verifyNoInteractions(diagnoser, matcher, jobs, gateway);
    }

    @Test
    void rejectsOversizedInputsBeforeAnyIo() {
        invalid(() -> tools.searchJobs("a".repeat(101), null, null, null, null), "query");
        invalid(() -> tools.searchJobs("Java", "a".repeat(61), null, null, null), "site");
        invalid(() -> tools.searchJobs("Java", null, null, "a".repeat(61), null), "location");
        invalid(() -> tools.diagnoseResume("a".repeat(50001), null), "resumeText");
        invalid(() -> tools.diagnoseResume("Java", "a".repeat(20001)), "jobDescription");
        invalid(() -> tools.matchJob("a".repeat(50001), "Java"), "resumeText");
        invalid(() -> tools.matchJob("Java", "a".repeat(20001)), "jobDescription");
        verifyNoInteractions(diagnoser, matcher, jobs, gateway);
    }

    @Test
    void rejectsInvalidNatureAndLimit() {
        for (String nature : new String[]{"", "contract", "campus,intern"})
            invalid(() -> tools.searchJobs("Java", null, nature, null, null), "nature");
        for (int limit : new int[]{0, -1, 21, Integer.MAX_VALUE})
            invalid(() -> tools.searchJobs("Java", null, null, null, limit), "limit");
        verifyNoInteractions(diagnoser, matcher, jobs, gateway);
    }

    @Test
    void searchProjectsCompactUnicodeSafeItemsAndPreservesSourceMetadata() throws Exception {
        var json = new ObjectMapper();
        var source = json.createObjectNode().put("fetchedAt", "2026-10-11T00:00:00Z");
        var job = source.putArray("items").addObject().put("id", "job1").put("url", "https://example.com/job1")
                .put("title", "AI后端").put("company", "示例公司").put("locations", "北京").put("source", "LOCAL_SNAPSHOT")
                .put("description", "字".repeat(238) + "😀" + "长描述".repeat(500)).put("requirement", "Java、Redis");
        source.withObject("meta").put("source", "LOCAL_SNAPSHOT").put("snapshotDate", "2026-10-10");
        source.withArray("items").add(job.deepCopy().put("id", "job2"));
        when(jobs.search("Java", "", "all", "")).thenReturn(source);
        var result = tools.searchJobs("Java", null, null, null, 1);
        assertThat(result.path("items")).hasSize(1);
        var item = result.path("items").get(0);
        assertThat(item.path("excerpt").asText()).isEqualTo("字".repeat(238) + "…").doesNotContain("\uD83D");
        assertThat(item.path("excerptTruncated").asBoolean()).isTrue();
        assertThat(item.has("description") || item.has("requirement")).isFalse();
        assertThat(item.path("url").asText()).isEqualTo(job.path("url").asText());
        assertThat(result.path("meta")).isEqualTo(source.path("meta"));
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(source.path("items").get(0).has("description")).isTrue();
        assertThat(result.toString().length()).isLessThan(1200);
    }

    @Test
    void serverDisabledDoesNotCreateToolsOrProvider() {
        new ApplicationContextRunner().withUserConfiguration(OfferPilotMcpTools.class, OfferPilotMcpServerConfig.class)
                .withPropertyValues("spring.ai.mcp.server.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OfferPilotMcpTools.class);
                    assertThat(context).doesNotHaveBean("offerPilotMcpToolCallbacks");
                });
    }

    private void invalid(Runnable call, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(ex.getMessage()).contains(field);
        });
    }
}
