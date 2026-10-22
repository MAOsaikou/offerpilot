package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Latest READY resume's saved current version. No contacts or unsaved browser draft. */
@Service
public class ResumeJobSignals {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public ResumeJobSignals(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public String skills(String owner) {
        var rows=jdbc.query("SELECT v.structured FROM resumes r JOIN resume_versions v ON v.id=r.current_version_id AND v.user_id=r.user_id WHERE r.user_id=? AND r.parse_status='READY' ORDER BY r.updated_at DESC,r.id LIMIT 1",
                (rs,n)->rs.getString(1),owner);
        if(rows.isEmpty()) return "";
        try {
            JsonNode node=json.readTree(rows.get(0)); StringBuilder text=new StringBuilder();
            for(String field:java.util.List.of("skills","summary","projects","experience","experiences","education")) collect(node.path(field),text);
            return com.offerpilot.common.text.TextBounds.truncate(text.toString(),12000);
        } catch(java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    private static void collect(JsonNode node,StringBuilder out) {
        if(node.isTextual()) out.append(node.asText()).append(' ');
        else if(node.isContainerNode()) node.forEach(child->collect(child,out));
    }
}
