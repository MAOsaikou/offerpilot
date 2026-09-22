package com.offerpilot.agent.experts;

import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.Map;

/** M1 read-only placeholders: no business operation or external tool is performed. */
@Component
public class ExpertRegistry {
    private static final Map<ExpertType, String> LABELS = Map.of(
            ExpertType.CAREER_DISCOVERY, "职业探索", ExpertType.RESUME, "简历指导",
            ExpertType.JOB, "岗位分析", ExpertType.INTERVIEWER, "面试练习", ExpertType.COACH, "成长教练");

    public String label(ExpertType type) { return LABELS.get(type); }

    public String capabilities() {
        return java.util.Arrays.stream(ExpertType.values())
                .map(type -> type.name() + ": " + label(type)).collect(java.util.stream.Collectors.joining("\n"));
    }

    public Mono<String> execute(PlanStep step, Map<String, String> upstreamOutputs) {
        return Mono.fromSupplier(() -> label(step.agent()) + "占位专家已接收任务；收到 "
                + upstreamOutputs.size() + " 项上游结果。仅验证编排链路，未执行搜索、修改或创建业务记录。");
    }
}
