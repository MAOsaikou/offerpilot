package com.offerpilot.career.application;

import java.util.List;

public interface CoachPlanForChat {
    record Target(String type, String id, String title) {}
    record Evidence(String type, String id, String label, String excerpt, String jobId) {
        public Evidence(String type, String id, String label, String excerpt) { this(type, id, label, excerpt, null); }
    }
    record Skill(String name, int required, int current, int gap, List<String> evidence, boolean learnFirst,
                 String id, List<Evidence> evidenceSources) {
        public Skill(String name, int required, int current, int gap, List<String> evidence, boolean learnFirst) {
            this(name, required, current, gap, evidence, learnFirst, null, List.of());
        }
    }
    record Citation(String docId, String title) {}
    record SkillGapPayload(String id, Target target, List<Skill> skills, String summary, List<Citation> citations) {}
    record Item(String id, String title, int hours, List<String> resources, String dueAt, boolean done) {}
    record Week(int index, String theme, List<Item> items, List<String> gapIds, List<Evidence> evidenceSources) {
        public Week(int index, String theme, List<Item> items) { this(index, theme, items, List.of(), List.of()); }
    }
    record Milestone(String title, String at) {}
    record RoadmapPayload(List<Week> weeks, List<Milestone> milestones) {}
    record Result(String summary, SkillGapPayload skillGap, RoadmapPayload roadmap) {}
    Result plan(String owner, String resumeId, String versionId, String requestId);
    default Result plan(String owner, String resumeId, String versionId, String requestId, String jobId, String applicationId) {
        return plan(owner, resumeId, versionId, requestId);
    }
    CareerProfileService.Profile adoptGrowthPlan(String owner, List<String> taskTitles);
}
