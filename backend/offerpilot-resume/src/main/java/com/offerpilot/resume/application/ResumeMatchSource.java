package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/** Small, owner-checked application boundary for matching an explicitly selected immutable version. */
@Service
public class ResumeMatchSource {
    private final ResumeService resumes;
    public ResumeMatchSource(ResumeService resumes) { this.resumes = resumes; }
    public record Snapshot(String resumeId, String title, String versionId, int versionNo, JsonNode structured) {}
    public Snapshot snapshot(String owner, String resumeId, String versionId) {
        var resume = resumes.get(owner, resumeId);
        if (!"READY".equals(resume.parseStatus())) throw new BusinessException(ErrorCode.RESUME_BUSY);
        var version = resumes.version(owner, resumeId, versionId);
        // Contact information is not relevant to assessing job requirements.
        var data = version.structured().deepCopy();
        if (data.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) data).remove(java.util.List.of("basics", "confidence"));
        return new Snapshot(resumeId, resume.title(), version.id(), version.versionNo(), data);
    }
}
