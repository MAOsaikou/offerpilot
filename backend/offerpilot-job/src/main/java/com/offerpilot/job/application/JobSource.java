package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.mcp.JobsMcpClient;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/** One source policy for REST, chat and the backend MCP tools, before any HTTP call. */
@Component
public class JobSource {
    public enum Mode { LIVE, LOCAL, AUTO }
    private final JobsMcpClient live;
    private final JobSnapshot snapshot;
    private final Mode mode;

    public JobSource(JobsMcpClient live, JobSnapshot snapshot,
                     @Value("${offerpilot.jobs.source-mode:${JOBS_SOURCE_MODE:AUTO}}") String mode) {
        this.live = live;
        this.snapshot = snapshot;
        this.mode = resolveMode(mode);
    }

    public static Mode resolveMode(String value) {
        try { return Mode.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException ex) {
            LoggerFactory.getLogger(JobSource.class).warn("Unsupported JOBS_SOURCE_MODE (including legacy ALL); using AUTO");
            return Mode.AUTO;
        }
    }

    public JsonNode sites() {
        return sites(Long.MAX_VALUE);
    }
    JsonNode sites(long deadline) {
        if (mode == Mode.LOCAL) return snapshot.sites();
        try {
            var result = deadline == Long.MAX_VALUE ? live.call("list_sites", Map.of())
                    : live.call("list_sites", Map.of(), java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
            if (result == null || !result.path("sites").isArray()) throw new BusinessException(ErrorCode.TOOL_FAILED);
            return result;
        } catch (BusinessException ex) {
            if (mode == Mode.LIVE || ex.errorCode() != ErrorCode.TOOL_FAILED) throw ex;
            return snapshot.sites();
        }
    }

    public JsonNode search(Map<String, Object> args) {
        if (mode == Mode.LOCAL) return local(args, null);
        try {
            var result = live.call("search_jobs", args);
            if (result == null || !result.path("jobs").isArray() || !result.path("_meta").isObject())
                throw new BusinessException(ErrorCode.TOOL_FAILED);
            var meta = result.path("_meta");
            boolean allFailed = meta.path("sites").size() > 0
                    && meta.path("skipped").size() == meta.path("sites").size();
            if (allFailed) {
                if (mode == Mode.AUTO) return local(args, "ALL_SITES_FAILED");
                throw new BusinessException(ErrorCode.TOOL_FAILED, "本次查询的企业站点均不可用，请稍后重试或选择其他企业");
            }
            if (mode == Mode.AUTO && result.path("jobs").isEmpty() && !meta.path("skipped").isEmpty())
                return local(args, "EMPTY_DUE_TO_ERROR");
            return result;
        } catch (BusinessException ex) {
            if (mode == Mode.LIVE || ex.errorCode() != ErrorCode.TOOL_FAILED) throw ex;
            return local(args, "LIVE_UNAVAILABLE");
        }
    }

    private JsonNode local(Map<String, Object> args, String reason) {
        if (reason != null) LoggerFactory.getLogger(JobSource.class).warn("Jobs live search unavailable; using local snapshot ({})", reason);
        return snapshot.search((String) args.get("query"), (String) args.getOrDefault("site", ""),
                (String) args.get("nature"), (String) args.getOrDefault("location", ""),
                ((Number) args.get("limit")).intValue(), reason);
    }

    public boolean localOnly() { return mode == Mode.LOCAL; }
    public boolean fallbackToStored() { return mode == Mode.AUTO; }

    public JsonNode detail(String site, String externalId, String nature) {
        if (externalId.startsWith("snapshot-")) return snapshot.detail(site, externalId);
        if (mode == Mode.LOCAL) throw new BusinessException(ErrorCode.JOB_NOT_FOUND);
        return live.call("get_job_detail", Map.of("site", site, "jobId", externalId, "nature", nature)).path("job");
    }
}
