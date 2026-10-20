package com.offerpilot.job.application;
import com.offerpilot.auth.application.UserDirectory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
/** Reconcile stored real jobs against saved profiles. No background MCP/model traffic. */
@Service
public class NewJobNotifications {
    private final UserDirectory users; private final JobRecommendationService recommendations;
    private String cursor="";
    public NewJobNotifications(UserDirectory users,JobRecommendationService recommendations) { this.users=users;this.recommendations=recommendations; }
    @Scheduled(initialDelayString="${offerpilot.notifications.new-job-initial-delay-ms:45000}",fixedDelayString="${offerpilot.notifications.new-job-delay-ms:60000}")
    public void scheduled() {
        var owners=users.activeAfter(cursor,10);
        for(String owner:owners) {
            try { recommendations.daily(owner); }
            catch(RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Job notification deferred: {}",ex.getClass().getSimpleName()); }
            cursor=owner;
        }
        if(owners.size()<10) cursor="";
    }
}
