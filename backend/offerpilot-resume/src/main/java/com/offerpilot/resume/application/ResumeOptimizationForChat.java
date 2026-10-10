package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Chat generates suggestions or reads a scoped saved report, never adopts patches. */
@Service
public class ResumeOptimizationForChat {
    private final ResumeOptimizationService optimizations;
    public ResumeOptimizationForChat(ResumeOptimizationService optimizations) { this.optimizations = optimizations; }

    public Mono<JsonNode> generate(String owner, String resumeId, String versionId, String jd, String requestId) {
        return generate(owner,resumeId,versionId,jd,requestId,null);
    }

    public Mono<JsonNode> generate(String owner, String resumeId, String versionId, String jd, String requestId, String jobId) {
        return Mono.fromCallable(() -> optimizations.begin(owner, resumeId, versionId,
                        new ResumeOptimizationService.Input(requestId, jd, jobId)))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnDiscard(ResumeOptimizationService.Work.class, work -> optimizations.cancel(owner, work.id()))
                .flatMap(optimizations::execute);
    }

    public Mono<JsonNode> report(String owner, String resumeId, String versionId, String reportId) {
        return Mono.fromCallable(() -> {
            var task = optimizations.get(owner, reportId);
            if (!resumeId.equals(task.path("resumeId").asText()) || !versionId.equals(task.path("versionId").asText()))
                throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "改写记录不属于所选简历版本");
            if (!"SUCCEEDED".equals(task.path("status").asText()) || !task.has("report"))
                throw new BusinessException(ErrorCode.RESUME_BUSY, "改写建议尚未完成，请查看改写记录");
            return task.path("report");
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
