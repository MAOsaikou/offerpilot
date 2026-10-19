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
                          List<String> completedTasks, String updatedAt, String major, String courses,
                          String valuesText, CareerAssessmentService.Confirmed assessment) {
        public Profile(String target, String city, String skills, List<String> interests,
                       List<String> completedTasks, String updatedAt) {
            this(target, city, skills, interests, completedTasks, updatedAt, "", "", "", null);
        }
    }

    @Transactional(readOnly = true)
    public Profile get(String owner) {
        // One statement reads the profile and checklist from the same database snapshot.
        var rows = jdbc.query("""
                SELECT p.*, a.confirmed, COALESCE((SELECT jsonb_agg(task_title) FROM growth_task_completions
                    WHERE user_id=p.user_id), '[]'::jsonb) AS completed
                FROM career_profiles p LEFT JOIN career_assessments a ON a.user_id=p.user_id WHERE p.user_id=?
                """, (rs, row) -> {
            var completed = strings(rs.getString("completed"));
            return new Profile(rs.getString("target"), rs.getString("city"), rs.getString("skills"),
                    strings(rs.getString("interests")),
                    GrowthTaskCatalog.TITLES.stream().filter(completed::contains).toList(),
                    rs.getTimestamp("updated_at").toInstant().toString(), rs.getString("major"), rs.getString("courses"),
                    rs.getString("values_text"), assessment(rs.getString("confirmed")));
        }, owner);
        if (!rows.isEmpty()) return rows.get(0);
        var confirmed = jdbc.query("SELECT confirmed FROM career_assessments WHERE user_id=?",
                (rs, row) -> assessment(rs.getString("confirmed")), owner);
        return new Profile("", "", "", List.of(), List.of(), null, "", "", "",
                confirmed.isEmpty() ? null : confirmed.get(0));
    }

    @Transactional
    public Profile save(String owner, String target, String city, String skills, List<String> interests) {
        return save(owner, target, city, skills, interests, null, null, null);
    }

    @Transactional
    public Profile save(String owner, String target, String city, String skills, List<String> interests,
                        String major, String courses, String valuesText) {
        // Optional new fields preserve values when an older client writes only the original fields.
        jdbc.update("""
                INSERT INTO career_profiles(user_id,target,city,skills,interests,major,courses,values_text)
                VALUES (?,?,?,?,?::jsonb,coalesce(?,''),coalesce(?,''),coalesce(?,''))
                ON CONFLICT(user_id) DO UPDATE SET target=EXCLUDED.target, city=EXCLUDED.city,
                    skills=EXCLUDED.skills, interests=EXCLUDED.interests,
                    major=coalesce(?,career_profiles.major), courses=coalesce(?,career_profiles.courses),
                    values_text=coalesce(?,career_profiles.values_text)
                """, owner, target, city, skills, encode(interests), major, courses, valuesText, major, courses, valuesText);
        return get(owner);
    }

    @Transactional
    public Profile saveTarget(String owner, String target) {
        // Read current fields under the row lock: edits during the approval wait must survive.
        jdbc.update("INSERT INTO career_profiles(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING", owner);
        jdbc.queryForObject("SELECT user_id FROM career_profiles WHERE user_id=? FOR UPDATE", String.class, owner);
        var current = get(owner);
        return save(owner, target, current.city(), current.skills(), current.interests());
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

    /** Add completions under the same profile-row lock as checklist replacement; preserve concurrent edits. */
    @Transactional
    public Profile mergeTasks(String owner, List<String> titles) {
        if (titles == null || titles.isEmpty() || titles.size() > GrowthTaskCatalog.TITLES.size()
                || !GrowthTaskCatalog.TITLES.containsAll(titles))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        jdbc.update("INSERT INTO career_profiles(user_id) VALUES (?) ON CONFLICT(user_id) DO UPDATE SET updated_at=now()", owner);
        var merged = new java.util.LinkedHashSet<>(get(owner).completedTasks());
        merged.addAll(titles);
        return replaceTasks(owner, List.copyOf(merged));
    }

    private CareerAssessmentService.Confirmed assessment(String raw) {
        try {
            return raw == null ? null : json.readValue(raw, CareerAssessmentService.Confirmed.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid stored career assessment JSON", ex);
        }
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
