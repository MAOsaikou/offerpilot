package com.offerpilot.common.artifact;

import java.util.List;

/** Cross-domain application port, assembled by bootstrap; no domain repository dependencies. */
public interface JobArtifacts {
    record Resume(String id, String resumeId, String resumeVersionId, String createdAt, String status,
                  String viewUrl, boolean pdfAvailable, String exportUrl) {}
    record Interview(String id, String status, Integer overallScore, String createdAt, String recapExportUrl) {}
    record Story(String id, String title, String sourceSessionId) {}
    record Linked(List<Resume> resumes, List<Interview> interviews, List<Story> stories) {}
    default String interviewContext(String owner, String jobId) { return null; }
    void lockJob(String owner, String jobId);
    void linkJob(String owner, String jobId);
    void linkSession(String owner, String sessionId);
    Linked read(String owner, String jobId);
    String selectedJob(String owner, String jobId, String applicationId, String matchId);
}
