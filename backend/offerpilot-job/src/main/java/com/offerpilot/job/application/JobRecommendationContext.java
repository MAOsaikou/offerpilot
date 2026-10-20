package com.offerpilot.job.application;

/** Read-only application port, assembled in bootstrap without a job -> career dependency. */
public interface JobRecommendationContext {
    record Context(String target,String city,String profileSkills,String resumeSkills) {}
    Context read(String owner);
}
