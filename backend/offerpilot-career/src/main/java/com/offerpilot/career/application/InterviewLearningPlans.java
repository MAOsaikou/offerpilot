package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.*;
import com.offerpilot.common.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;

@Service
public class InterviewLearningPlans {
    public record Plan(String id, String reportId, String startDate, CoachPlanForChat.RoadmapPayload roadmap) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InterviewLearningSource source;
    private final CoachPlanForChat coach;
    public InterviewLearningPlans(JdbcTemplate jdbc, ObjectMapper json, InterviewLearningSource source, CoachPlanForChat coach) {
        this.jdbc=jdbc; this.json=json; this.source=source; this.coach=coach;
    }
    @Transactional
    public Plan create(String owner, String reportId) {
        // Same owned session lock as answer/progress writes; retries and concurrent clicks converge.
        var report = source.read(owner, reportId);
        var existing = list(owner).stream().filter(p -> p.reportId().equals(reportId)).findFirst();
        if (existing.isPresent()) return existing.get();
        if (report.topics().isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "报告暂无可转为学习任务的短板。");
        var roadmap = coach.fromInterview(report);
        String id = IdGenerator.next("lp");
        String start = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        try {
            jdbc.update("INSERT INTO interview_learning_plans(id,user_id,report_id,roadmap,start_date) VALUES (?,?,?,?::jsonb,?::date) ON CONFLICT(user_id,report_id) DO NOTHING",
                    id, owner, reportId, json.writeValueAsString(roadmap), start);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        return list(owner).stream().filter(p -> p.reportId().equals(reportId)).findFirst().orElseThrow();
    }
    @Transactional(readOnly=true)
    public List<Plan> list(String owner) {
        return jdbc.query("SELECT id,report_id,start_date,roadmap::text FROM interview_learning_plans WHERE user_id=? ORDER BY created_at DESC,id", (rs, row) -> {
            try { return new Plan(rs.getString("id"), rs.getString("report_id"), rs.getString("start_date"),
                    json.readValue(rs.getString("roadmap"), CoachPlanForChat.RoadmapPayload.class)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid stored roadmap", e); }
        }, owner);
    }
    @Transactional(readOnly=true)
    public String calendar(String owner, String id) {
        var plan = list(owner).stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND));
        return LearningCalendar.export(plan);
    }
}
