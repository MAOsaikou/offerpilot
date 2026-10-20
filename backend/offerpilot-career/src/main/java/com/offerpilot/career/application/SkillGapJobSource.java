package com.offerpilot.career.application;

import java.util.List;

/** Bootstrap supplies a saved, owner-visible job; never refreshes the external job source. */
public interface SkillGapJobSource {
    record Job(String id, String title, String description, List<String> skills) {}
    Job read(String owner, String jobId);
}
