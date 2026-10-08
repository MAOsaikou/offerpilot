package com.offerpilot.job.application;

/** Read the selected stored job for a rewrite; never search or refresh implicitly. */
public interface JobDescriptionForChat {
    String description(String jobId);
}
