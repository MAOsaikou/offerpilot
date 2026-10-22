package com.offerpilot.resume.application;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import java.time.Duration;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ResumeDiagnosisForChatTest {
    @Test void discussionReadsTheExplicitHistoricalVersionWithoutGeneratingAnotherReport() {
        var service = mock(ResumeDiagnosisService.class);
        var chat = new ResumeDiagnosisForChat(service);
        var report = new ObjectMapper().createObjectNode().put("versionId", "old-version");
        when(service.report("owner", "resume", "old-version", "report")).thenReturn(report);
        assertThat(chat.execute("owner", "resume", "old-version", "report", "unused").block(Duration.ofSeconds(3))).isEqualTo(report);
        verify(service, never()).analyze(any(), any(), any(), any());
        when(service.report("stranger", "resume", "old-version", "report")).thenThrow(new IllegalArgumentException("unavailable"));
        assertThatThrownBy(() -> chat.execute("stranger", "resume", "old-version", "report", "unused").block(Duration.ofSeconds(3))).hasMessageContaining("unavailable");
        when(service.analyze("owner", "resume", "new-version", "request")).thenReturn(Mono.just(report));
        assertThat(chat.execute("owner", "resume", "new-version", null, "request").block(Duration.ofSeconds(3))).isEqualTo(report);
        verify(service).analyze("owner", "resume", "new-version", "request");
    }
}
