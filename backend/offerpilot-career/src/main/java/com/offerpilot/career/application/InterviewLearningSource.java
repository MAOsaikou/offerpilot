package com.offerpilot.career.application;
import java.util.List;

/** Must verify owner and completed status before exposing report evidence. */
public interface InterviewLearningSource {
    record Topic(String title, String reason, int hours) {}
    record Report(String id, String role, List<Topic> topics) {}
    Report read(String owner, String reportId);
}
