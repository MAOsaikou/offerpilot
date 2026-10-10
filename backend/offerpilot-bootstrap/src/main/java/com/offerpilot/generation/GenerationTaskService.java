package com.offerpilot.generation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Cross-feature RUNNING generations for the progress dock. Read-only. */
@Service
public class GenerationTaskService {
    public record Task(String id, String kind, String status, String title, String href,
                       String createdAt, String modelTier) {}

    private final JdbcTemplate jdbc;

    public GenerationTaskService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
}
