package com.offerpilot.artifact;

import com.offerpilot.career.application.SkillGapJobSource;
import com.offerpilot.job.application.JobService;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

@Component
public class SkillGapJobBridge implements SkillGapJobSource {
    private final JobService jobs;
    public SkillGapJobBridge(JobService jobs) { this.jobs = jobs; }
    public Job read(String owner, String jobId) {
        var node = jobs.get(owner, jobId);
        var skills = new ArrayList<String>();
        if (node.path("skills").isArray()) node.path("skills").forEach(s -> {
            if (s.isTextual() && !s.asText().isBlank() && s.asText().length() <= 100 && skills.size() < 100) skills.add(s.asText());
        });
        return new Job(jobId, node.path("title").asText(), node.path("description").asText(), skills);
    }
}
