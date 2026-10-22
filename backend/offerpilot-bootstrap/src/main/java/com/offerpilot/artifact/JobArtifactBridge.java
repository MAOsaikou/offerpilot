package com.offerpilot.artifact;

import com.offerpilot.common.artifact.JobArtifacts;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.interview.application.InterviewService;
import com.offerpilot.interview.application.InterviewRubric;
import com.offerpilot.job.application.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bootstrap composes shared job visibility, owned artifacts and the application journal. */
@Service
public class JobArtifactBridge implements JobArtifacts {
    private final JdbcTemplate jdbc;
    private final JobService jobs;
    public JobArtifactBridge(JdbcTemplate jdbc, JobService jobs) { this.jdbc=jdbc; this.jobs=jobs; }

    public String interviewContext(String owner, String jobId) {
        if (jobId == null) return null;
        var job = jobs.get(owner, jobId);
        String text = job.path("title").asText() + "：" + job.path("description").asText("");
        return text.substring(0, text.offsetByCodePoints(0, Math.min(400, text.codePointCount(0, text.length()))));
    }

    public void lockJob(String owner, String jobId) {
        if (jobId == null) return;
        if (!jobId.matches("\\S{1,80}")) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        // Same lock as application creation: either writer observes the other's committed artifact.
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",String.class,owner).isEmpty())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        jobs.get(owner, jobId); // shared snapshots, or a job this user imported
    }
    public String selectedJob(String owner, String jobId, String applicationId, String matchId) {
        if (jobId != null) { jobs.get(owner, jobId); return jobId; }
        if (applicationId != null) return ownedJob("applications", owner, applicationId, ErrorCode.APPLICATION_NOT_FOUND);
        if (matchId != null) return ownedJob("job_matches", owner, matchId, ErrorCode.MATCH_NOT_FOUND);
        return null;
    }
    private String ownedJob(String table, String owner, String id, ErrorCode error) {
        var rows=jdbc.queryForList("SELECT job_id FROM "+table+" WHERE user_id=? AND id=?",String.class,owner,id);
        if (rows.isEmpty()) throw new BusinessException(error);
        return rows.get(0);
    }
    public void linkSession(String owner, String sessionId) {
        var jobs=jdbc.queryForList("SELECT job_id FROM interview_sessions WHERE user_id=? AND id=? AND job_id IS NOT NULL",String.class,owner,sessionId);
        if (!jobs.isEmpty()) linkJob(owner,jobs.get(0));
    }
    public void linkJob(String owner, String jobId) {
        if (jobId == null) return;
        jdbc.update("""
            INSERT INTO application_events(id,application_id,kind,detail,artifact_type,artifact_id,actor)
            SELECT 'evt_artifact_' || a.id || '_' || x.type || '_' || x.id,a.id,'ARTIFACT_LINKED',x.detail,x.type,x.id,'SYSTEM'
            FROM applications a JOIN (
                SELECT user_id,job_id,id,'RESUME_OPTIMIZATION' AS type,'定向简历改写已关联' AS detail FROM resume_optimizations
                UNION ALL SELECT user_id,job_id,id,'INTERVIEW','面试练习已关联' FROM interview_sessions
                UNION ALL SELECT s.user_id,i.job_id,s.id,'STAR_STORY','STAR 故事已关联'
                    FROM star_stories s JOIN interview_sessions i ON i.id=s.source_session_id AND i.user_id=s.user_id
            ) x ON x.user_id=a.user_id AND x.job_id=a.job_id
            WHERE a.user_id=? AND a.job_id=? ON CONFLICT DO NOTHING
            """,owner,jobId);
    }
    public Linked read(String owner,String jobId) {
        var resumes=jdbc.query("""
            SELECT o.*,r.parse_status FROM resume_optimizations o JOIN resumes r ON r.id=o.resume_id AND r.user_id=o.user_id
            WHERE o.user_id=? AND o.job_id=? ORDER BY o.created_at DESC,o.id DESC
            """,(rs,n)->new Resume(rs.getString("id"),rs.getString("resume_id"),rs.getString("version_id"),
                rs.getTimestamp("created_at").toInstant().toString(),rs.getString("status"),
                "/resumes/"+rs.getString("resume_id")+"?tab=optimize&optimizationReportId="+rs.getString("id")+"&resumeVersionId="+rs.getString("version_id")+"&jobId="+jobId,
                "READY".equals(rs.getString("parse_status")),"/api/v1/resumes/"+rs.getString("resume_id")+"/export.pdf"),owner,jobId);
        // Load all answers once; the drawer may have many practice sessions for this job.
        record ScopedAnswer(String sessionId, InterviewService.Answer answer) {}
        var answersBySession=jdbc.query("""
            SELECT a.* FROM interview_answers a JOIN interview_sessions i ON i.id=a.session_id
            WHERE i.user_id=? AND i.job_id=? ORDER BY a.session_id,a.question_index
            """,(rs,n)->new ScopedAnswer(rs.getString("session_id"),new InterviewService.Answer(rs.getInt("question_index"),rs.getString("question_text"),rs.getString("answer_text"))),owner,jobId)
            .stream().collect(java.util.stream.Collectors.groupingBy(ScopedAnswer::sessionId,
                java.util.stream.Collectors.mapping(ScopedAnswer::answer,java.util.stream.Collectors.toList())));
        var interviews=jdbc.query("SELECT * FROM interview_sessions WHERE user_id=? AND job_id=? ORDER BY created_at DESC,id DESC",(rs,n)->{
            String id=rs.getString("id");
            var answers=answersBySession.getOrDefault(id,java.util.List.of());
            var session=new InterviewService.Session(id,rs.getString("role"),rs.getString("kind"),rs.getInt("question_index"),rs.getBoolean("completed"),
                rs.getTimestamp("created_at").toInstant().toString(),rs.getTimestamp("updated_at").toInstant().toString(),answers);
            Integer score=answers.stream().anyMatch(a->!a.answerText().isBlank()) ? InterviewRubric.score(session).overall() : null;
            return new Interview(id,session.completed()?"COMPLETED":"IN_PROGRESS",score,session.createdAt(),"/api/v1/interviews/"+id+"/recap.md");
        },owner,jobId);
        var stories=jdbc.query("""
            SELECT s.id,s.title,s.source_session_id FROM star_stories s JOIN interview_sessions i ON i.id=s.source_session_id AND i.user_id=s.user_id
            WHERE s.user_id=? AND i.job_id=? ORDER BY s.created_at DESC,s.id DESC
            """,(rs,n)->new Story(rs.getString("id"),rs.getString("title"),rs.getString("source_session_id")),owner,jobId);
        return new Linked(resumes,interviews,stories);
    }
}
