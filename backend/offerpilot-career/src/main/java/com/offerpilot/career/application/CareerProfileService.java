package com.offerpilot.career.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CareerProfileService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CareerProfileService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Profile(String target, String city, String skills, List<String> interests,
                          List<String> completedTasks, String updatedAt) {}

    @Transactional(readOnly = true)
    public Profile get(String owner) {
        // One statement reads the profile and checklist from the same database snapshot.
        var rows = jdbc.query("""
                SELECT p.*, COALESCE((SELECT jsonb_agg(task_title) FROM growth_task_completions
                    WHERE user_id=p.user_id), '[]'::jsonb) AS completed
                FROM career_profiles p WHERE p.user_id=?
                """, (rs, row) -> {
            var completed = strings(rs.getString("completed"));
            return new Profile(rs.getString("target"), rs.getString("city"), rs.getString("skills"),
                    strings(rs.getString("interests")),
                    GrowthTaskCatalog.TITLES.stream().filter(completed::contains).toList(),
                    rs.getTimestamp("updated_at").toInstant().toString());
        }, owner);
        return rows.isEmpty() ? new Profile("", "", "", List.of(), List.of(), null) : rows.get(0);
    }

    @Transactional
    public Profile save(String owner, String target, String city, String skills, List<String> interests) {
        jdbc.update("""
                INSERT INTO career_profiles(user_id,target,city,skills,interests) VALUES (?,?,?,?,?::jsonb)
                ON CONFLICT(user_id) DO UPDATE SET target=EXCLUDED.target, city=EXCLUDED.city,
                    skills=EXCLUDED.skills, interests=EXCLUDED.interests
                """, owner, target, city, skills, encode(interests));
        return get(owner);
    }

    @Transactional
    public Profile replaceTasks(String owner, List<String> completed) {
        if (!GrowthTaskCatalog.TITLES.containsAll(completed))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        // This shared profile row serializes concurrent checklist replacements and profile writes.
        jdbc.update("""
                INSERT INTO career_profiles(user_id) VALUES (?)
                ON CONFLICT(user_id) DO UPDATE SET updated_at=now()
                """, owner);
        var existing = jdbc.queryForList(
                "SELECT task_title FROM growth_task_completions WHERE user_id=?", String.class, owner);
        for (String title : existing) {
            if (!completed.contains(title))
                jdbc.update("DELETE FROM growth_task_completions WHERE user_id=? AND task_title=?", owner, title);
        }
        for (String title : completed.stream().distinct().toList()) {
            jdbc.update("""
                    INSERT INTO growth_task_completions(user_id,task_title) VALUES (?,?)
                    ON CONFLICT(user_id,task_title) DO NOTHING
                    """, owner, title);
        }
        return get(owner);
    }

    private List<String> strings(String value) {
        try {
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid stored career profile JSON", ex);
        }
    }

    private String encode(List<String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize career interests", ex);
        }
    }
}
