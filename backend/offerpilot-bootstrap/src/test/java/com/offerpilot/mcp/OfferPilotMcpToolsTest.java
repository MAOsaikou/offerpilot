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
