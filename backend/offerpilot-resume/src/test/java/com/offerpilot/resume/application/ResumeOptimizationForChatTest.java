package com.offerpilot.resume.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeOptimizationForChatTest {
    @Test void savedReportIsBoundToOwnerResumeAndVersionAndCannotRegenerate() {
        var service = mock(ResumeOptimizationService.class);
        var facade = new ResumeOptimizationForChat(service);
        var task = new ObjectMapper().createObjectNode().put("resumeId", "r1").put("versionId", "v1").put("status", "SUCCEEDED");
        var report = task.putObject("report").put("reportId", "o1");
        when(service.get("owner", "o1")).thenReturn(task);
        assertThat(facade.report("owner", "r1", "v1", "o1").block()).isEqualTo(report);
        assertThatThrownBy(() -> facade.report("owner", "r2", "v1", "o1").block()).hasMessageContaining("不属于");
        assertThatThrownBy(() -> facade.report("owner", "r1", "v2", "o1").block()).hasMessageContaining("不属于");
        task.put("status", "RUNNING");
        assertThatThrownBy(() -> facade.report("owner", "r1", "v1", "o1").block()).hasMessageContaining("尚未完成");
        verify(service, never()).begin(any(), any(), any(), any());
        verify(service, never()).execute(any());
    }
}
