package com.offerpilot.artifact;

import com.offerpilot.interview.application.InterviewResumeSource;
import com.offerpilot.resume.application.ResumeMatchSource;
import org.springframework.stereotype.Service;

@Service
public class InterviewResumeBridge implements InterviewResumeSource {
    private final ResumeMatchSource resumes;
    public InterviewResumeBridge(ResumeMatchSource resumes) { this.resumes = resumes; }
    public String context(String owner, String resumeId, String versionId) {
        var data = resumes.snapshot(owner, resumeId, versionId).structured();
        var names = new java.util.ArrayList<String>();
        for (String field : java.util.List.of("projects", "skills", "experience")) for (var item : data.path(field)) {
            for (String key : java.util.List.of("name", "title", "tech", "category", "items")) {
                var value = item.path(key);
                if (value.isArray()) value.forEach(node -> { if (node.isTextual() && !node.asText().isBlank()) names.add(node.asText().strip()); });
                else if (value.isTextual() && !value.asText().isBlank()) names.add(value.asText().strip());
            }
        }
        if (names.isEmpty() && !data.path("summary").asText("").isBlank()) names.add(data.path("summary").asText());
        if (names.isEmpty()) return "所选简历版本未列出项目和技能，请补充真实经历";
        String text = String.join("、", names);
        return text.substring(0, text.offsetByCodePoints(0, Math.min(300, text.codePointCount(0, text.length()))));
    }
}
