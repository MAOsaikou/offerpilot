package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobSearchForChatTest {
    final ObjectMapper json = new ObjectMapper();
    final JobService jobs = mock(JobService.class);
    final JobSearchForChat search = new JobSearchForChat(jobs, json);

    @Test void resolvesKnownCompanyBoundsSnapshotAndPreservesPartialResultMetadata() throws Exception {
        when(jobs.sites()).thenReturn(json.readTree("{\"sites\":[{\"id\":\"didi\",\"name\":\"Didi\",\"supportedNatures\":[\"campus\"]}]}"));
        var result = json.createObjectNode().put("fetchedAt", "2026-09-28T00:00:00Z");
        var items = result.putArray("items");
        for (int i = 0; i < 30; i++) items.addObject().put("id", "job_" + i).put("title", "Java".repeat(1000)).put("description", "private".repeat(10000));
        result.set("meta", json.readTree("{\"partial\":true,\"timedOut\":true,\"cached\":true,\"truncated\":true,\"sites\":[\"didi\",\"offline\"],\"degradedSites\":[\"didi\"],\"skipped\":[{\"site\":\"offline\",\"reason\":\"secret\"}]}"));
        when(jobs.search("Java", "didi", "campus", "北京")).thenReturn(result);
        var card = search.search(" Java ", "滴滴", "campus", "北京");
        assertThat(card.path("items")).hasSize(5);
        assertThat(card.path("meta").path("partial").asBoolean()).isTrue();
        assertThat(card.path("meta").path("timedOut").asBoolean()).isTrue();
        assertThat(card.path("returnedCount").asInt()).isEqualTo(30);
        assertThat(card.path("criteria").path("site").asText()).isEqualTo("didi");
        assertThat(card.path("meta").path("truncated").asBoolean()).isTrue();
        assertThat(card.path("meta").path("degradedSites")).hasSize(1);
        assertThat(card.toString()).doesNotContain("private", "secret");
        assertThat(card.toString().length()).isLessThan(32768);
    }

    @Test void unknownCompanyAndUnsupportedNatureNeverBroadenToAllSites() throws Exception {
        when(jobs.sites()).thenReturn(json.readTree("{\"sites\":[{\"id\":\"didi\",\"name\":\"滴滴\",\"supportedNatures\":[\"campus\"]}]}"));
        assertThatThrownBy(() -> search.search("Java", "不存在", "all", "")).hasMessageContaining("未识别");
        assertThatThrownBy(() -> search.search("Java", "滴滴", "intern", "")).hasMessageContaining("不支持");
        verify(jobs, never()).search(anyString(), anyString(), anyString(), anyString());
    }
}
