package com.offerpilot.agent.application;

import com.offerpilot.career.application.CoachResumeSkills;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.resume.application.ResumeMatchSource;
import com.offerpilot.resume.application.ResumeService;
import org.springframework.stereotype.Component;
import java.util.*;

/** Bridges existing resume application readers into the career port without a module cycle. */
@Component
public class CoachResumeSkillsAdapter implements CoachResumeSkills {
    private final ResumeService resumes;
    private final ResumeMatchSource source;
    public CoachResumeSkillsAdapter(ResumeService resumes, ResumeMatchSource source) { this.resumes = resumes; this.source = source; }
    @Override public Snapshot read(String owner, String resumeId, String versionId) {
        try {
            if (resumeId == null && versionId == null) {
                var latest = resumes.latestReady(owner);
                if (latest == null) return new Snapshot(false, List.of());
                resumeId = latest.id(); versionId = latest.currentVersionId();
            }
            if (resumeId == null || versionId == null) return new Snapshot(false, List.of());
            var data = source.snapshot(owner, resumeId, versionId).structured();
            var names = new LinkedHashSet<String>();
            data.path("skills").forEach(group -> group.path("items").forEach(item -> names.add(item.asText())));
            data.path("projects").forEach(project -> project.path("tech").forEach(item -> names.add(item.asText())));
            return new Snapshot(true, List.copyOf(names), versionId);
        } catch (BusinessException ex) {
            if (ex.errorCode() != ErrorCode.RESUME_NOT_FOUND && ex.errorCode() != ErrorCode.RESUME_BUSY) throw ex;
            return new Snapshot(false, List.of());
        }
    }
}
