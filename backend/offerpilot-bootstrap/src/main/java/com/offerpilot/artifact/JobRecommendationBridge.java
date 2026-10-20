package com.offerpilot.artifact;

import com.offerpilot.job.application.JobRecommendationContext;
import com.offerpilot.career.application.CareerProfileService;
import com.offerpilot.resume.application.ResumeJobSignals;
import org.springframework.stereotype.Component;

@Component
public class JobRecommendationBridge implements JobRecommendationContext {
    private final CareerProfileService profiles; private final ResumeJobSignals resumes;
    public JobRecommendationBridge(CareerProfileService profiles,ResumeJobSignals resumes) { this.profiles=profiles; this.resumes=resumes; }
    public Context read(String owner) {
        var p=profiles.get(owner);
        return new Context(p.target(),p.city(),p.skills()+" "+p.major()+" "+p.courses()+" "+String.join(" ",p.interests()),resumes.skills(owner));
    }
}
