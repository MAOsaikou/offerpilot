package com.offerpilot.career.application;

import java.util.List;

/** Optional application port; returns only skill names, never contact details or raw resume text. */
public interface CoachResumeSkills {
    record Snapshot(boolean available, List<String> skills, String sourceId) {
        public Snapshot(boolean available, List<String> skills) { this(available, skills, null); }
    }
    Snapshot read(String owner, String resumeId, String versionId);
}
