package com.offerpilot.agent.domain;

import java.util.List;

public record Plan(String goal, List<PlanStep> steps) {
}
