package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Chat reads completed reports without triggering a second model analysis. */
@Service
public class ResumeDiagnosisForChat {
    private final ResumeDiagnosisService diagnoses;
    public ResumeDiagnosisForChat(ResumeDiagnosisService diagnoses) { this.diagnoses=diagnoses; }
    public Mono<JsonNode> execute(String owner,String resumeId,String versionId,String reportId,String requestId) {
        return reportId == null ? diagnoses.analyze(owner,resumeId,versionId,requestId)
            : Mono.fromCallable(() -> diagnoses.report(owner,resumeId,versionId,reportId)).subscribeOn(Schedulers.boundedElastic());
    }
}
