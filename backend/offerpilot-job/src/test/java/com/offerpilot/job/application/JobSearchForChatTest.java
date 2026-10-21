package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobSearchForChatTest {
    final ObjectMapper json = new ObjectMapper();
    final JobService jobs = mock(JobService.class);
    final JobSearchForChat search = new JobSearchForChat(jobs, json);

    @Test void emptyPartialResultFailsVisiblyButNormalZeroMatchesStillReturnsCard() {
        var result = json.createObjectNode();
        result.putArray("items");
        result.putObject("meta").put("partial", true);
        when(jobs.search(eq("Java"), eq(""), eq("all"), eq(""), anyLong())).thenReturn(result);
        assertThatThrownBy(() -> search.search("Java", "", "all", ""))
                .isInstanceOfSatisfying(com.offerpilot.common.error.BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(com.offerpilot.common.error.ErrorCode.TOOL_FAILED);
                    assertThat(ex.getMessage()).contains("均不可用");
                });
        result.withObject("/meta").put("partial", false);
        assertThat(search.search("Java", "", "all", "").path("items")).isEmpty();
    }

    @Test void resolvesKnownCompanyBoundsSnapshotAndPreservesPartialResultMetadata() throws Exception {
        when(jobs.sites(anyLong())).thenReturn(json.readTree("{\"sites\":[{\"id\":\"didi\",\"name\":\"Didi\",\"supportedNatures\":[\"campus\"]}]}"));
        var result = json.createObjectNode().put("fetchedAt", "2026-09-28T00:00:00Z");
        var items = result.putArray("items");
        for (int i = 0; i < 30; i++) items.addObject().put("id", "job_" + i).put("title", "Java".repeat(1000)).put("description", "private".repeat(10000));
        result.set("meta", json.readTree("{\"partial\":true,\"timedOut\":true,\"cached\":true,\"truncated\":true,\"sites\":[\"didi\",\"offline\"],\"degradedSites\":[\"didi\"],\"skipped\":[{\"site\":\"offline\",\"reason\":\"secret\"}]}"));
        when(jobs.search(eq("Java"), eq("didi"), eq("campus"), eq("北京"), anyLong())).thenReturn(result);
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
        when(jobs.sites(anyLong())).thenReturn(json.readTree("{\"sites\":[{\"id\":\"didi\",\"name\":\"滴滴\",\"supportedNatures\":[\"campus\"]}]}"));
        assertThatThrownBy(() -> search.search("Java", "不存在", "all", "")).hasMessageContaining("未识别");
        assertThatThrownBy(() -> search.search("Java", "滴滴", "intern", "")).hasMessageContaining("不支持");
        verify(jobs, never()).search(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test void failedDirectoryStillSearchesLocalDatabaseWithinOriginalDeadline() {
        when(jobs.sites(anyLong())).thenThrow(new com.offerpilot.common.error.BusinessException(
                com.offerpilot.common.error.ErrorCode.TOOL_FAILED));
        var result=json.createObjectNode();
        result.putArray("items").addObject().put("id","stored").put("retrievalSource","LOCAL_DB");
        result.putObject("meta").put("partial",true);
        when(jobs.search(eq("Java"),eq("didi"),eq("all"),eq(""),anyLong())).thenReturn(result);
        long start=System.nanoTime();
        assertThat(search.search("Java","滴滴","all","").path("items")).hasSize(1);
        var directoryDeadline=org.mockito.ArgumentCaptor.forClass(Long.class);
        var searchDeadline=org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(jobs).sites(directoryDeadline.capture());
        verify(jobs).search(eq("Java"),eq("didi"),eq("all"),eq(""),searchDeadline.capture());
        assertThat(searchDeadline.getValue()-directoryDeadline.getValue())
                .isEqualTo(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1700));
        assertThat(searchDeadline.getValue()-start).isLessThan(java.util.concurrent.TimeUnit.SECONDS.toNanos(8));
    }
}
