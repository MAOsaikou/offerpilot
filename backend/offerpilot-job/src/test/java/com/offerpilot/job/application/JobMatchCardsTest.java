package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class JobMatchCardsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsValidatedReportWithoutSnapshotsOrInventedDimensions() throws Exception {
        ObjectNode report = (ObjectNode) json.readTree("""
                {"id":"match_01abc","jobId":"job_1","resumeId":"res_1","resumeVersionId":"ver_1",
                 "score":50,"summary":"证据可用","requirements":[
                   {"requirement":"Java","status":"MET","resumeQuote":"做过 Java","suggestion":"保留项目"},
                   {"requirement":"SQL","status":"PARTIAL","resumeQuote":"写过查询","suggestion":"补充索引"},
                   {"requirement":"K8s","status":"MISSING","resumeQuote":"","suggestion":"补充容器经历"}]}
                """);
        report.set("jobSnapshot", json.createObjectNode().put("title", "快照"));
        report.set("resumeSnapshot", json.createObjectNode().put("summary", "快照"));

        JobMatchCards.Card card = JobMatchCards.fromReport(report);

        assertThat(card.cardId()).isEqualTo("match_01abc");
        assertThat(card.cardType()).isEqualTo("job_match");
        assertThat(card.summary()).isEqualTo("匹配分 50。证据可用");
        assertThat(card.payload().has("jobSnapshot")).isFalse();
        assertThat(card.payload().has("resumeSnapshot")).isFalse();
        assertThat(card.payload().has("dimensions")).isFalse();
        assertThat(card.payload().path("requirements")).hasSize(3);
        assertThat(card.payload().path("matched")).hasSize(1);
        assertThat(card.payload().path("matched").get(0).path("strength").asText()).isEqualTo("STRONG");
        assertThat(card.payload().path("matched").get(0).path("evidence").asText()).isEqualTo("做过 Java");
        assertThat(card.payload().path("gaps")).hasSize(2);
        assertThat(card.payload().path("gaps").get(0).path("severity").asText()).isEqualTo("MEDIUM");
        assertThat(card.payload().path("gaps").get(1).path("severity").asText()).isEqualTo("MEDIUM");
        assertThat(card.payload().path("advice")).extracting(node -> node.asText())
                .containsExactly("保留项目", "补充索引", "补充容器经历");
        assertThat(card.payload().path("evaluation").path("dimensions")).hasSize(6);
        assertThat(card.payload().path("evaluation").path("grade").asText()).isNotBlank();
        assertThat(report.has("jobSnapshot")).isTrue();
    }

    @Test
    void rejectsReportWithoutId() {
        assertThatThrownBy(() -> JobMatchCards.fromReport(json.createObjectNode().put("score", 1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void chatEntryRejectsRequestIdsTheHttpApiWouldReject() {
        JobMatchService matches = mock(JobMatchService.class);
        JobMatchForChat service = new JobMatchForChatService(matches);
        assertThatThrownBy(() -> service.match("owner", "job_1", "res_1", "ver_1", "run_has_underscore"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(matches);
    }
}
