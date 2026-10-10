package com.offerpilot.interview.application;

/** Text-only application boundary shared by chat; every resource is resolved using the trusted owner. */
public interface InterviewCoachForChat {
    record Turn(String sessionId, String state, int questionIndex, int total, String question, String note) {}
    InterviewService.Session session(String owner, String id);
    String resolveSession(String owner, String sessionId, boolean activeOnly);
    Turn startSession(String owner, String role, String kind);
    Turn startSession(String owner, String role, String kind, String jobId);
    Turn continueSession(String owner, String sessionId, String answer, boolean nextQuestion, String expectedUpdatedAt);
    InterviewRubric.Report scoreSession(String owner, String sessionId);
    InterviewRubric.Report completeSession(String owner, String sessionId, String expectedUpdatedAt);
}
