package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.mcp.JobsMcpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobSourceTest {
    final ObjectMapper json = new ObjectMapper();
    final JobsMcpClient live = mock(JobsMcpClient.class);
    final JobSnapshot snapshot = new JobSnapshot(json);
    final Map<String, Object> args = Map.of("query", "Java", "site", "bytedance", "nature", "campus", "limit", 30);

    @Test void autoFallsBackOnLiveFailureWithProvenance() {
        when(live.call("search_jobs", args)).thenThrow(new BusinessException(ErrorCode.TOOL_FAILED));
        var result = new JobSource(live, snapshot, "AUTO").search(args);
        assertThat(result.path("jobs")).isNotEmpty();
        assertThat(result.at("/_meta/source").asText()).isEqualTo("LOCAL_SNAPSHOT");
        assertThat(result.at("/_meta/fallbackReason").asText()).isEqualTo("LIVE_UNAVAILABLE");
        assertThat(result.at("/_meta/snapshotDate").asText()).isEqualTo("2026-10-10");
    }

    @Test void liveStillSurfacesFailure() {
        when(live.call("search_jobs", args)).thenThrow(new BusinessException(ErrorCode.TOOL_FAILED));
        assertThatThrownBy(() -> new JobSource(live, snapshot, "LIVE").search(args))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TOOL_FAILED));
    }

    @Test void localNeverCallsLiveForSearchDirectoryOrDetail() {
        var source = new JobSource(live, snapshot, "LOCAL");
        var result = source.search(args);
        assertThat(result.path("jobs")).isNotEmpty();
        assertThat(result.path("_meta").has("fallbackReason")).isFalse();
        assertThat(source.sites().path("sites")).hasSize(8);
        assertThat(source.detail("bytedance", result.path("jobs").get(0).path("id").asText(), "campus").path("source").asText())
                .isEqualTo("LOCAL_SNAPSHOT");
        verifyNoInteractions(live);
    }

    @Test void localFiltersAndRankingAndLimitAreConsistent() {
        var result = snapshot.search("Java Spring", "字节跳动", "campus", "上海", 30, null);
        assertThat(result.path("jobs")).isNotEmpty().allSatisfy(job -> {
            assertThat(job.path("site").asText()).isEqualTo("bytedance");
            assertThat(job.path("nature").asText()).isEqualTo("campus");
            assertThat(job.path("locations").asText()).contains("上海");
            assertThat(job.path("id").asText()).startsWith("snapshot-");
            assertThat(job.path("url").asText()).isEmpty();
        });
        assertThat(snapshot.search("Java", "bytedance", "intern", "北京", 30, null).path("jobs")).isEmpty();
        assertThat(snapshot.search("Java", "bytedance", "campus", "不存在城市", 30, null).path("jobs")).isEmpty();
        var limited = snapshot.search("Java", "", "all", "", 1, null);
        assertThat(limited.path("jobs")).hasSize(1);
        assertThat(limited.at("/_meta/truncated").asBoolean()).isTrue();
        assertThat(limited.path("jobs").get(0).path("title").asText()).containsIgnoringCase("Java");
    }

    @ParameterizedTest @ValueSource(strings = {"ALL", "typo", "", "auto"})
    void legacyAndUnknownModesUseAuto(String value) {
        assertThat(JobSource.resolveMode(value)).isEqualTo(JobSource.Mode.AUTO);
        when(live.call("search_jobs", args)).thenThrow(new BusinessException(ErrorCode.TOOL_FAILED));
        assertThat(new JobSource(live, snapshot, value).search(args).at("/_meta/source").asText()).isEqualTo("LOCAL_SNAPSHOT");
    }

    @Test void allSitesAndEmptyDueToErrorFallBackButHealthyEmptyDoesNot() throws Exception {
        var source = new JobSource(live, snapshot, "AUTO");
        when(live.call("search_jobs", args)).thenReturn(json.readTree("""
            {"jobs":[],"_meta":{"sites":["bytedance"],"skipped":[{"site":"bytedance"}]}}
            """));
        assertThat(source.search(args).at("/_meta/fallbackReason").asText()).isEqualTo("ALL_SITES_FAILED");
        assertThatThrownBy(() -> new JobSource(live, snapshot, "LIVE").search(args)).isInstanceOf(BusinessException.class);
        when(live.call("search_jobs", args)).thenReturn(json.readTree("""
            {"jobs":[],"_meta":{"sites":["bytedance","tencent"],"skipped":[{"site":"bytedance"}]}}
            """));
        assertThat(source.search(args).at("/_meta/fallbackReason").asText()).isEqualTo("EMPTY_DUE_TO_ERROR");
        when(live.call("search_jobs", args)).thenReturn(json.readTree("{\"jobs\":[],\"_meta\":{\"sites\":[\"bytedance\"],\"skipped\":[]}}"));
        assertThat(source.search(args).path("jobs")).isEmpty();
        assertThat(source.search(args).path("_meta").has("fallbackReason")).isFalse();
    }
}
