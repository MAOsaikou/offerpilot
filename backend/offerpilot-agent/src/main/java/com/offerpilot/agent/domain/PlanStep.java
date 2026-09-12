package com.offerpilot.agent.domain;

import java.util.List;
import java.util.Map;

public record PlanStep(
        String id,
        String title,
        ExpertType agent,
        Map<String, Object> inputs,
        List<String> dependsOn
) {
}
