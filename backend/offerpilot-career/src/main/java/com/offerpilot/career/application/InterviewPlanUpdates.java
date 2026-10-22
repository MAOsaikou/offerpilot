package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.common.error.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Deterministic proposals from already screened report evidence. No model call or completion writes. */
@Service
public class InterviewPlanUpdates {
    public record Suggestion(String planId, String reportId, String targetRole, String fingerprint,
                             List<CoachPlanForChat.Week> additions) {}
    record Stored(InterviewLearningPlans.Plan plan, String role, String jobId, JsonNode applied) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InterviewLearningSource source;
    private final InterviewLearningPlans plans;
    private final CoachPlanForChat coach;
    public InterviewPlanUpdates(JdbcTemplate jdbc, ObjectMapper json, InterviewLearningSource source,
                                InterviewLearningPlans plans, CoachPlanForChat coach) {
        this.jdbc=jdbc; this.json=json; this.source=source; this.plans=plans; this.coach=coach;
    }
    @Transactional
    public List<Suggestion> preview(String owner, String reportId) {
        plans.lockOwner(owner);
        var report=source.read(owner,reportId);
        return stored(owner).stream().filter(s -> eligible(s,report))
                .map(s -> suggestion(s,report)).toList();
    }
    @Transactional
    public InterviewLearningPlans.Plan confirm(String owner, String reportId, String planId, String fingerprint) {
        if (fingerprint==null || !fingerprint.matches("[a-f0-9]{64}")) throw conflict();
        plans.lockOwner(owner);
        var report=source.read(owner,reportId); // owner, completed status and current saved report rechecked
        var stored=stored(owner).stream().filter(s -> s.plan().id().equals(planId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND));
        String digest=hash(report);
        var applied=stored.applied().path(reportId);
        if (sameTarget(stored.role(),stored.jobId(),report) && digest.equals(applied.path("reportDigest").asText())
                && fingerprint.equals(applied.path("fingerprint").asText())) return stored.plan();
        if (!eligible(stored,report)) throw conflict();
        var suggestion=suggestion(stored,report);
        if (!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.UTF_8),
                suggestion.fingerprint().getBytes(StandardCharsets.UTF_8))) throw conflict();
        var roadmap=merge(stored.plan().roadmap(),suggestion.additions());
        var history=stored.applied().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)history).putObject(reportId)
                .put("reportDigest",digest).put("fingerprint",fingerprint);
        jdbc.update("UPDATE interview_learning_plans SET roadmap=?::jsonb,applied_updates=?::jsonb WHERE id=? AND user_id=?",
                write(roadmap),write(history),planId,owner);
        return new InterviewLearningPlans.Plan(planId,stored.plan().reportId(),stored.plan().startDate(),roadmap);
    }
    private boolean eligible(Stored stored, InterviewLearningSource.Report report) {
        return !stored.plan().reportId().equals(report.id()) && !report.topics().isEmpty()
                && sameTarget(stored.role(),stored.jobId(),report)
                && !hash(report).equals(stored.applied().path(report.id()).path("reportDigest").asText());
    }
    static boolean sameTarget(String role, String jobId, InterviewLearningSource.Report report) {
        if (jobId!=null || report.jobId()!=null) return jobId!=null && jobId.equals(report.jobId());
        // No fuzzy/semantic role matching: both sources must explicitly carry the same role.
        return role!=null && !role.isBlank() && role.strip().equalsIgnoreCase(report.role().strip());
    }
    private Suggestion suggestion(Stored stored, InterviewLearningSource.Report report) {
        String reportDigest=hash(report);
        var additions=coach.fromInterview(report).weeks().stream().map(week -> new CoachPlanForChat.Week(week.index(),week.theme(),
                week.items().stream().map(item -> new CoachPlanForChat.Item(item.id()+"-"+reportDigest,item.title(),
                        item.hours(),item.resources(),item.dueAt(),false)).toList(),week.gapIds(),week.evidenceSources())).toList();
        return new Suggestion(stored.plan().id(),report.id(),stored.role(),
                hash(List.of(stored.plan(), report, stored.role(), Objects.toString(stored.jobId(),""), stored.applied(), additions)), additions);
    }
    static CoachPlanForChat.RoadmapPayload merge(CoachPlanForChat.RoadmapPayload base, List<CoachPlanForChat.Week> additions) {
        var weeks=new ArrayList<CoachPlanForChat.Week>(base.weeks());
        for (var extra:additions) {
            if (extra.items().isEmpty()) continue;
            int at=-1;
            for(int i=0;i<weeks.size();i++) if(weeks.get(i).index()==extra.index()) { at=i; break; }
            var old=at<0?new CoachPlanForChat.Week(extra.index(),extra.theme(),List.of()):weeks.get(at);
            var items=new ArrayList<>(old.items());
            var ids=new HashSet<>(items.stream().map(CoachPlanForChat.Item::id).toList());
            for(var item:extra.items()) if(ids.add(item.id())) items.add(new CoachPlanForChat.Item(
                    item.id(),item.title(),item.hours(),item.resources(),item.dueAt(),false));
            var evidence=new LinkedHashSet<>(old.evidenceSources()); evidence.addAll(extra.evidenceSources());
            var merged=new CoachPlanForChat.Week(old.index(),old.theme(),List.copyOf(items),old.gapIds(),List.copyOf(evidence));
            if(at<0) weeks.add(merged); else weeks.set(at,merged);
        }
        weeks.sort(Comparator.comparingInt(CoachPlanForChat.Week::index));
        return new CoachPlanForChat.RoadmapPayload(List.copyOf(weeks),base.milestones());
    }
    private List<Stored> stored(String owner) {
        return jdbc.query("SELECT * FROM interview_learning_plans WHERE user_id=? ORDER BY id FOR UPDATE",(rs,n) -> {
            try {
                return new Stored(new InterviewLearningPlans.Plan(rs.getString("id"),rs.getString("report_id"),
                        rs.getString("start_date"),json.readValue(rs.getString("roadmap"),CoachPlanForChat.RoadmapPayload.class)),
                        rs.getString("target_role"),rs.getString("target_job_id"),json.readTree(rs.getString("applied_updates")));
            } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        },owner);
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.RESUME_VERSION_CONFLICT,"报告或学习计划已变化，请重新查看计划更新建议。");
    }
}
