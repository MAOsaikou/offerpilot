package com.offerpilot.board.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.job.application.JobMatchService;
import com.offerpilot.job.application.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class ApplicationService {
    private final JdbcTemplate jdbc;
    private final JobService jobs;
    private final JobMatchService matches;
    private final ApplicationTimeline timeline;
    public ApplicationService(JdbcTemplate jdbc, JobService jobs, JobMatchService matches, ApplicationTimeline timeline) {
        this.jdbc=jdbc; this.jobs=jobs; this.matches=matches; this.timeline=timeline;
    }
    public record Application(String id, String jobId, String status, String note,
                              String createdAt, String updatedAt, JsonNode job, String nextAction, String nextActionAt) {
        public Application(String id, String jobId, String status, String note, String createdAt, String updatedAt, JsonNode job) {
            this(id,jobId,status,note,createdAt,updatedAt,job,"",null);
        }
    }
    // null means unchanged; empty date string explicitly clears it.
    public record Patch(String status, String note, String nextAction, String nextActionAt) {}
    public record Match(String id, String createdAt, String grade, List<String> topGaps, String exportUrl) {}
    public record Event(String id, String kind, String fromStatus, String toStatus, String detail,
                        String artifactType, String artifactId, String actor, String createdAt) {}
    public record ResumeArtifact(String id, String resumeId, String resumeVersionId, String createdAt,
                                 boolean pdfAvailable, String exportUrl) {}
    public record InterviewArtifact(String id, String status, Integer overallScore, String createdAt, String recapExportUrl) {}
    public record StoryArtifact(String id, String title, String sourceSessionId) {}
    public record Detail(Application application, Match latestMatch, List<ResumeArtifact> resumeOptimizations,
                         List<InterviewArtifact> interviews, List<StoryArtifact> starStories, List<Event> timeline, String linkageNote) {}
    private Application view(ResultSet rs, int row) throws SQLException {
        return new Application(rs.getString("id"),rs.getString("job_id"),rs.getString("status"),rs.getString("note"),
            rs.getTimestamp("created_at").toInstant().toString(),rs.getTimestamp("updated_at").toInstant().toString(),
            jobs.get(rs.getString("job_id")),rs.getString("next_action"),rs.getString("next_action_at"));
    }
    @Transactional(readOnly=true)
    public List<Application> list(String owner) {
        return jdbc.query("SELECT * FROM applications WHERE user_id=? ORDER BY created_at DESC,id",this::view,owner);
    }
    @Transactional(readOnly=true)
    public Detail detail(String owner, String id) {
        Application app = require(owner,id,false);
        var ids = jdbc.queryForList("""
            SELECT id FROM job_matches WHERE user_id=? AND job_id=? AND status='COMPLETED'
            ORDER BY created_at DESC,id DESC LIMIT 1
            """,String.class,owner,app.jobId());
        Match latest = null;
        if (!ids.isEmpty()) {
            JsonNode report = matches.get(owner,ids.get(0));
            var gaps = new java.util.ArrayList<String>();
            // Keep the report's order, but prioritize missing requirements ahead of partial evidence.
            for (String status : List.of("MISSING","PARTIAL"))
                for (JsonNode requirement : report.path("requirements"))
                    if (gaps.size()<3 && status.equals(requirement.path("status").asText()))
                        gaps.add(requirement.path("requirement").asText());
            latest = new Match(ids.get(0),report.path("createdAt").asText(),report.path("evaluation").path("grade").asText(),
                List.copyOf(gaps),"/api/v1/jobs/matches/"+ids.get(0)+"/eval.md");
        }
        var events = jdbc.query("""
            SELECT e.* FROM application_events e JOIN applications a ON a.id=e.application_id
            WHERE a.id=? AND a.user_id=? ORDER BY e.created_at,e.id
            """,(rs,row)->new Event(rs.getString("id"),rs.getString("kind"),rs.getString("from_status"),
                rs.getString("to_status"),rs.getString("detail"),rs.getString("artifact_type"),rs.getString("artifact_id"),
                rs.getString("actor"),rs.getTimestamp("created_at").toInstant().toString()),id,owner);
        return new Detail(app,latest,List.of(),List.of(),List.of(),events,
            "匹配报告按同一用户和岗位关联。简历改写只保存 JD 文本，面试只保存岗位名称，STAR 只引用面试会话；这些材料没有岗位 ID，暂不自动关联。");
    }
    private Application require(String owner, String id, boolean lock) {
        var rows=jdbc.query("SELECT * FROM applications WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),this::view,id,owner);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.APPLICATION_NOT_FOUND);
        return rows.get(0);
    }
    @Transactional
    public Application create(String owner, String jobId) { return createBase(owner,jobId,"USER",null); }
    private Application createBase(String owner,String jobId,String actor,String runId) {
        jobs.get(jobId);
        // Serialize creation with match completion; either side sees the other's committed artifact.
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",String.class,owner).isEmpty())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        String id=IdGenerator.next("app");
        int inserted=jdbc.update("INSERT INTO applications(id,user_id,job_id) VALUES (?,?,?) ON CONFLICT(user_id,job_id) DO NOTHING",id,owner,jobId);
        var saved=jdbc.queryForObject("SELECT * FROM applications WHERE user_id=? AND job_id=?",this::view,owner,jobId);
        if(inserted==1) timeline.record(saved.id(),"CREATED",null,"TODO","加入求职看板",actor,runId);
        timeline.linkMatches(owner,jobId);
        return saved;
    }
    @Transactional
    public Application create(String owner, String jobId, String status, String note) {
        return createWithPatch(owner,jobId,new Patch(status==null?"TODO":status,note,null,null),null);
    }
    @Transactional
    public Application createWithPatch(String owner,String jobId,Patch patch,String runId) {
        validate(patch);
        Application saved=createBase(owner,jobId,"AGENT",runId);
        return updateWithPatch(owner,saved.id(),patch,"AGENT",runId);
    }
    @Transactional
    public Application update(String owner, String id, String status, String note) {
        return updateWithPatch(owner,id,new Patch(status,note,null,null),"USER",null);
    }
    @Transactional
    public Application updateWithPatch(String owner,String id,Patch patch,String actor,String runId) {
        validate(patch);
        Application old=require(owner,id,true);
        String status=patch.status()==null?old.status():patch.status();
        String note=patch.note()==null?old.note():patch.note();
        String action=patch.nextAction()==null?old.nextAction():patch.nextAction();
        String date=patch.nextActionAt()==null?old.nextActionAt():patch.nextActionAt().isEmpty()?null:patch.nextActionAt();
        if (!old.status().equals(status)) timeline.record(id,"STATUS_CHANGED",old.status(),status,"",actor,runId);
        if (!old.note().equals(note)) timeline.record(id,"NOTE_EDITED",null,null,note,actor,runId);
        if (!old.nextAction().equals(action) || !Objects.equals(old.nextActionAt(),date))
            timeline.record(id,"NEXT_ACTION_EDITED",null,null,action+(date==null?"":" · "+date),actor,runId);
        if (old.status().equals(status) && old.note().equals(note) && old.nextAction().equals(action) && Objects.equals(old.nextActionAt(),date)) return old;
        jdbc.update("UPDATE applications SET status=?,note=?,next_action=?,next_action_at=?::date,updated_at=now() WHERE id=? AND user_id=?",
            status,note,action,date,id,owner);
        return require(owner,id,false);
    }
    public static void validate(Patch patch) {
        if ((patch.status()==null && patch.note()==null && patch.nextAction()==null && patch.nextActionAt()==null)
                || (patch.status()!=null && !Set.of("TODO","APPLIED","INTERVIEW","OFFER","CLOSED").contains(patch.status()))
                || (patch.note()!=null && patch.note().codePointCount(0,patch.note().length())>2000)
                || (patch.nextAction()!=null && patch.nextAction().codePointCount(0,patch.nextAction().length())>1000))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if(patch.nextActionAt()!=null && !patch.nextActionAt().isEmpty()) {
            try {
                if (!patch.nextActionAt().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("date",patch.nextActionAt(),0);
                if (LocalDate.parse(patch.nextActionAt()).getYear()<1) throw new DateTimeParseException("date",patch.nextActionAt(),0);
            } catch(DateTimeParseException ex) { throw new BusinessException(ErrorCode.VALIDATION_FAILED,"行动日期须为有效的 YYYY-MM-DD，空字符串可清除日期"); }
        }
    }
}
