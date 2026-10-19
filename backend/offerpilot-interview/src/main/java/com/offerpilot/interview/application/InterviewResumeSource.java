package com.offerpilot.interview.application;

/** Owner-checked resume version context, assembled by bootstrap. No contact data. */
public interface InterviewResumeSource {
    String context(String owner, String resumeId, String versionId);
}
