package com.offerpilot.generation;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.job.application.JobMatchService;
import com.offerpilot.resume.application.ResumeDiagnosisService;
import com.offerpilot.resume.application.ResumeOptimizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Cross-feature RUNNING generations for the progress dock. */
@Service
public class GenerationTaskService {
    public static final Set<String> KINDS = Set.of("JOB_MATCH", "RESUME_OPTIMIZE", "RESUME_DIAGNOSIS");

    public record Task(String id, String kind, String status, String title, String href,
                       String createdAt, String modelTier) {}

    private final JdbcTemplate jdbc;
    private final JobMatchService matches;
    private final ResumeOptimizationService optimizations;
    private final ResumeDiagnosisService diagnoses;

    public GenerationTaskService(JdbcTemplate jdbc, JobMatchService matches,
                                 ResumeOptimizationService optimizations, ResumeDiagnosisService diagnoses) {
        this.jdbc = jdbc;
        this.matches = matches;
        this.optimizations = optimizations;
        this.diagnoses = diagnoses;
    }

    public List<Task> listRunning(String owner) {
        var tasks = new ArrayList<Task>();
        tasks.addAll(jdbc.query("""
                SELECT id, job_id, created_at, report->>'modelTier' AS model_tier
                FROM job_matches WHERE user_id=? AND status='RUNNING'
                ORDER BY created_at DESC LIMIT 10
                """, (rs, n) -> new Task(rs.getString("id"), "JOB_MATCH", "RUNNING",
                "岗位匹配分析", "/jobs/" + rs.getString("job_id"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("model_tier")), owner));
        tasks.addAll(jdbc.query("""
                SELECT id, resume_id, created_at, model_tier FROM resume_optimizations
                WHERE user_id=? AND status='RUNNING' ORDER BY created_at DESC LIMIT 10
                """, (rs, n) -> new Task(rs.getString("id"), "RESUME_OPTIMIZE", "RUNNING",
                "简历定向改写", "/resumes/" + rs.getString("resume_id"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("model_tier")), owner));
        tasks.addAll(jdbc.query("""
                SELECT id, resume_id, created_at, model_tier FROM resume_diagnoses
                WHERE user_id=? AND status='RUNNING' ORDER BY created_at DESC LIMIT 10
                """, (rs, n) -> new Task(rs.getString("id"), "RESUME_DIAGNOSIS", "RUNNING",
                "简历诊断", "/resumes/" + rs.getString("resume_id"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("model_tier")), owner));
        tasks.sort(Comparator.comparing(Task::createdAt).reversed());
        return List.copyOf(tasks.stream().limit(15).toList());
    }

    /** Cancel a RUNNING task owned by the caller; finished tasks are idempotent no-ops. */
    public void cancel(String owner, String kind, String id) {
        if (id == null || id.isBlank() || id.length() > 80) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "任务标识无效");
        }
        String normalized = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的任务类型");
        }
        switch (normalized) {
            case "JOB_MATCH" -> matches.cancel(owner, id);
            case "RESUME_OPTIMIZE" -> optimizations.cancel(owner, id);
            case "RESUME_DIAGNOSIS" -> diagnoses.cancel(owner, id);
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的任务类型");
        }
    }
}
