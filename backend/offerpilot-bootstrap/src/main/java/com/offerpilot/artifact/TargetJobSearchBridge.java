package com.offerpilot.artifact;
import com.offerpilot.career.application.TargetJobSearch;
import com.offerpilot.job.application.JobService;
import com.offerpilot.llm.quota.LlmUserContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
@Service
public class TargetJobSearchBridge implements TargetJobSearch {
    private final JobService jobs;
    public TargetJobSearchBridge(JobService jobs) { this.jobs=jobs; }
    public JsonNode search(String owner,String target,String city) {
        return LlmUserContext.asUser(owner,()->jobs.search(target,"","all",city));
    }
}
