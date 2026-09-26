package com.offerpilot.agent.experts;

import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.job.application.JobMatchCards;
import com.offerpilot.job.application.JobMatchForChat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** 占位专家，以及在调用方给出真实标识时执行的岗位匹配。 */
@Component
public class ExpertRegistry {
    private static final Map<ExpertType, String> LABELS = Map.of(
            ExpertType.CAREER_DISCOVERY, "职业探索", ExpertType.RESUME, "简历指导",
            ExpertType.JOB, "岗位分析", ExpertType.INTERVIEWER, "面试练习", ExpertType.COACH, "成长教练");
    private final JobMatchForChat matches;

    public ExpertRegistry() {
        this.matches = null;
    }

    public ExpertRegistry(JobMatchForChat matches) {
        this.matches = matches;
    }

    @Autowired
    public ExpertRegistry(ObjectProvider<JobMatchForChat> matches) {
        this.matches = matches.getIfAvailable();
    }

    public String label(ExpertType type) {
        return LABELS.get(type);
    }

    public String capabilities() {
        return java.util.Arrays.stream(ExpertType.values())
                .map(type -> type.name() + ": " + label(type)).collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 岗位步骤是否会调用已有匹配。标识不齐或匹配服务不存在时保持占位。 */
    public boolean matching(PlanStep step, Map<String, Object> context) {
        return matches != null && step.agent() == ExpertType.JOB && target(step, context) != null;
    }

    public Mono<ExpertResult> execute(String owner, Map<String, Object> context, String runId, PlanStep step,
                                      Map<String, String> upstreamOutputs) {
        Target target = step.agent() == ExpertType.JOB ? target(step, context) : null;
        if (target != null && matches != null) {
            String requestId = requestId(runId, step.id());
            return Mono.fromCallable(() -> {
                JobMatchCards.Card card = matches.match(owner, target.jobId(), target.resumeId(), target.resumeVersionId(), requestId);
                return ExpertResult.card(card.summary(), card.cardId(), card.cardType(), card.payload());
            }).subscribeOn(Schedulers.boundedElastic());
        }
        return Mono.fromSupplier(() -> ExpertResult.text(label(step.agent()) + "占位专家已接收任务；收到 "
                + upstreamOutputs.size() + " 项上游结果。仅验证编排链路，未执行搜索、修改或创建业务记录。"));
    }

    private Target target(PlanStep step, Map<String, Object> context) {
        Target fromInputs = read(step.inputs());
        if (fromInputs != null) return fromInputs;
        return read(context);
    }

    private static Target read(Map<String, Object> source) {
        if (source == null) return null;
        String jobId = text(source.get("jobId"));
        String resumeId = text(source.get("resumeId"));
        String versionId = text(source.get("resumeVersionId"));
        if (jobId == null || resumeId == null || versionId == null) return null;
        return new Target(jobId, resumeId, versionId);
    }

    private static String text(Object value) {
        if (!(value instanceof String raw)) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > 80) return null;
        return trimmed;
    }

    static String requestId(String runId, String stepId) {
        return UUID.nameUUIDFromBytes((runId + ":" + stepId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record Target(String jobId, String resumeId, String resumeVersionId) {
    }
}
