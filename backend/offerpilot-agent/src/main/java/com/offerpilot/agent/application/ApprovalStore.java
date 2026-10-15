package com.offerpilot.agent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable copy of board-write approvals in the V3 {@code approvals} table, so a pending
 * approval survives a closed chat stream, page reload or backend restart. Claims are
 * conditional updates on {@code status='PENDING'}, so each approval is decided exactly once.
 */
@Component
public class ApprovalStore {
    public record Row(String id, String runId, String action, Map<String, Object> args, String status, Instant expiresAt, Instant createdAt) {}

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ApprovalStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(String id, String owner, String runId, String action, Map<String, Object> args, Instant expiresAt) {
        jdbc.update("INSERT INTO approvals(id,run_id,user_id,tool_name,args,status,expires_at) VALUES (?,?,?,?,CAST(? AS jsonb),'PENDING',?)",
                id, runId, owner, action, write(args), Timestamp.from(expiresAt));
    }

    /** Records the decision only if the approval is still pending and unexpired; false when someone else won. */
    boolean claim(String id, String owner, String status, Map<String, Object> finalArgs, String note) {
        return jdbc.update("""
                UPDATE approvals SET status=?, edited_args=CAST(? AS jsonb), decision_note=?, decided_at=now()
                WHERE id=? AND user_id=? AND status='PENDING' AND expires_at>now()
                """, status, write(finalArgs), note, id, owner) == 1;
    }

    void timeout(String id) {
        jdbc.update("UPDATE approvals SET status='TIMEOUT', decided_at=now() WHERE id=? AND status='PENDING'", id);
    }

    Optional<Row> find(String owner, String id) {
        return jdbc.query("SELECT * FROM approvals WHERE id=? AND user_id=?", (rs, i) -> row(rs), id, owner).stream().findFirst();
    }

    List<Row> pending(String owner, List<String> actions) {
        return jdbc.query("""
                SELECT * FROM approvals WHERE user_id=? AND status='PENDING' AND expires_at>now() AND tool_name = ANY(?)
                ORDER BY created_at DESC LIMIT 20
                """, (rs, i) -> row(rs), owner, actions.toArray(String[]::new));
    }

    private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new Row(rs.getString("id"), rs.getString("run_id"), rs.getString("tool_name"), json.readValue(rs.getString("args"), MAP),
                    rs.getString("status"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审批参数无法读取", e);
        }
    }

    private String write(Map<String, Object> args) {
        try {
            return args == null ? null : json.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("审批参数无法保存", e);
        }
    }
}
