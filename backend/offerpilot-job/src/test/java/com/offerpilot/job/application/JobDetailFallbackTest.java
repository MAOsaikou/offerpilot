package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.mcp.JobsMcpClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobDetailFallbackTest {
    final ObjectMapper json = new ObjectMapper();
    final JobsMcpClient live = mock(JobsMcpClient.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectNode stored(String site, String source) {
        return json.createObjectNode().put("id", "job1").put("site", site).put("source", source)
                .put("externalId", "external1").put("nature", "intern").put("requirement", "Java")
                .put("detailFetchedAt", "2020-01-01T00:00:00Z");
    }
    JobService service(String mode, ObjectNode stored) {
        var service = spy(new JobService(new JobSource(live, mock(JobSnapshot.class), mode), jdbc, json));
        doReturn(stored).when(service).get("u1", "job1");
        return service;
    }
    @Test void demoAndLocalSnapshotsNeverNeedLiveDetailEvenWhenForcedOrExpired() {
        for (String mode : new String[]{"AUTO", "LIVE", "LOCAL"}) {
            for (var job : new ObjectNode[]{stored("demo", "demo"), stored("tencent", "LOCAL_SNAPSHOT")}) {
                assertThat(service(mode, job).detail("u1", "job1", true)).isEqualTo(job);
                assertThat(service(mode, job).detail("u1", "job1", false)).isEqualTo(job);
            }
        }
        verifyNoInteractions(live, jdbc);
    }
    @Test void autoReturnsMarkedStoredCopyAndLiveKeepsFailure() {
        when(live.call(eq("get_job_detail"), anyMap())).thenThrow(new BusinessException(ErrorCode.TOOL_FAILED));
        var original = stored("tencent", "company-career-site");
        var fallback = service("AUTO", original).detail("u1", "job1", false);
        assertThat(fallback.path("source").asText()).isEqualTo("STORED_SNAPSHOT");
        assertThat(fallback.path("originalSource").asText()).isEqualTo("company-career-site");
        assertThat(fallback.path("requirement").asText()).isEqualTo("Java");
        assertThat(original.path("source").asText()).isEqualTo("company-career-site");
        assertThatThrownBy(() -> service("LIVE", original).detail("u1", "job1", true)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void autoHandlesMalformedDetailButDoesNotHideUnrelatedErrors() {
        when(live.call(anyString(), anyMap())).thenReturn(json.createObjectNode().set("job", json.createObjectNode()));
        assertThat(service("AUTO", stored("tencent", "company-career-site")).detail("u1", "job1", true)
                .path("source").asText()).isEqualTo("STORED_SNAPSHOT");
        when(live.call(anyString(), anyMap())).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service("AUTO", stored("tencent", "company-career-site")).detail("u1", "job1", true))
                .isInstanceOf(BusinessException.class).hasMessage(ErrorCode.VALIDATION_FAILED.message());
    }
}
