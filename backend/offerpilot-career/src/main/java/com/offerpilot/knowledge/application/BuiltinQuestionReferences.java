package com.offerpilot.knowledge.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;

/** Small application port. Bootstrap composes it with interview without a module dependency. */
@Service
public class BuiltinQuestionReferences {
    private final JdbcTemplate jdbc;
    public BuiltinQuestionReferences(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Reference(String id,String text,String type,String difficulty,String source,String notice) {}
    public List<Reference> find(String role,String industry,String difficulty) {
        if(role==null||role.isBlank()) return List.of();
        return jdbc.query("""
            SELECT id,content,question_type,difficulty,source,notice FROM builtin_knowledge_entries
            WHERE kind='QUESTION' AND status='READY' AND difficulty=?
            AND (role=? OR strpos(lower(?),lower(role))>0 OR strpos(lower(role),lower(?))>0)
            AND (?='' OR industry=?)
            ORDER BY row_number() OVER (PARTITION BY question_type ORDER BY id),question_type,id LIMIT 16
            """,(r,n) -> new Reference(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)),
            difficulty,role,role,role,Objects.toString(industry,""),Objects.toString(industry,""));
    }
}
