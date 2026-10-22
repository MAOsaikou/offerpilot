package com.offerpilot.resume.application;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ResumeParseWorkerTest {
    @Test void publishesReadStageBeforeExtractionAndOnlyCompletesAfterValidation() throws Exception {
        var service = mock(ResumeService.class);
        var store = mock(ResumeObjectStore.class);
        var extractor = mock(ResumeExtractor.class);
        var reader = new ResumeDocumentReader();
        var job = new ResumeService.Job("r", "owner", "lease-token", "bucket", "key", "text/plain", "FLASH", "FLASH");
        when(service.claim()).thenReturn(job);
        when(store.get("bucket", "key")).thenReturn("fictional document".getBytes());
        var json = new ObjectMapper();
        var structure = json.readTree(ResumeStructure.SCHEMA);
        var meta = json.createObjectNode().put("fixture", true);
        when(extractor.extractResult(any(), eq("FLASH"), eq("FLASH"))).thenAnswer(call -> {
            assertThat(com.offerpilot.llm.quota.LlmUserContext.current()).isEqualTo("owner");
            return new ResumeExtractor.Result(structure, meta);
        });
        var worker = new ResumeParseWorker(service, store, reader, extractor);
        worker.processNext();
        var order = inOrder(service, store, extractor);
        order.verify(service).claim(); order.verify(store).get("bucket", "key");
        order.verify(service).progress(job);
        order.verify(extractor).extractResult(any(), eq("FLASH"), eq("FLASH"));
        order.verify(service).complete(job, structure, meta);
        reset(service, extractor);
        when(service.claim()).thenReturn(job);
        when(extractor.extractResult(any(), any(), any())).thenThrow(new RuntimeException("private-provider-body"));
        worker.processNext();
        verify(service).fail(eq(job), argThat(message -> !message.contains("private-provider-body")));
        verify(service, never()).complete(any(), any(), any(com.fasterxml.jackson.databind.JsonNode.class));
    }
}
