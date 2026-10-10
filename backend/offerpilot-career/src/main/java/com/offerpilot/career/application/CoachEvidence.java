package com.offerpilot.career.application;

import java.util.List;

/** Read-only application port; resources are selected by code using the authenticated owner. */
public interface CoachEvidence {
    record Gap(String name, int priority, int score, CoachPlanForChat.Evidence source) {}
    record Snapshot(boolean matchAvailable, boolean interviewAvailable, List<Gap> gaps) {}
    Snapshot read(String owner, String jobId, String applicationId, String target);
}
