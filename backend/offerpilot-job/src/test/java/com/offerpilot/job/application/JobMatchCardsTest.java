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
