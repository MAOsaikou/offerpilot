package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.conversation.application.ConversationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

/** Durable ordered event history in V3; new runs include text and cards for faithful replay. */
@Component
public class WorkbenchEvents {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConversationService conversations;
    public WorkbenchEvents(JdbcTemplate jdbc, ObjectMapper json, ConversationService conversations) {
        this.jdbc = jdbc; this.json = json; this.conversations = conversations;
    }
    void append(AgentEvent event) {
        try {
            jdbc.update("INSERT INTO agent_events(run_id,seq,type,payload) VALUES (?,?,?,CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
                    event.runId(), event.id(), event.type(), json.writeValueAsString(event));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("运行事件无法保存", ex); }
    }
    void complete(String runId) {
        jdbc.update("UPDATE agent_runs SET events_finished=true WHERE id=?",runId);
    }
    public void authorize(String owner,String runId) { conversations.requireRunOwner(owner,runId); }
    public record Batch(List<AgentEvent> events, boolean active) {}
    public Batch tail(String owner,String runId,int after) {
        conversations.requireRunOwner(owner,runId);
        jdbc.update("UPDATE agent_runs SET reconnect_until=now()+interval '5 seconds' WHERE id=? AND user_id=?",runId,owner);
        boolean active=Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT events_finished AND (lease_expires_at IS NULL OR lease_expires_at>now()) FROM agent_runs WHERE id=? AND user_id=?",Boolean.class,runId,owner));
        // Read the completion marker BEFORE rows: a completed marker guarantees the following read sees the last append.
        var events=jdbc.query("SELECT payload FROM agent_events WHERE run_id=? AND seq>? ORDER BY seq LIMIT 500",(rs,n) -> {
            try { return json.readValue(rs.getString(1),AgentEvent.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        },runId,after);
        return new Batch(events,active);
    }
    boolean connected(String runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT reconnect_until>now() FROM agent_runs WHERE id=?",Boolean.class,runId));
    }
    public List<AgentEvent> read(String owner, String runId) {
        conversations.requireRunOwner(owner, runId);
        List<AgentEvent> events = new java.util.ArrayList<>(jdbc.query("SELECT payload FROM agent_events WHERE run_id=? ORDER BY seq", (rs, i) -> {
            try { return json.readValue(rs.getString(1), AgentEvent.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("运行事件无法读取", ex); }
        }, runId));
        var unresolved = new java.util.LinkedHashMap<String, AgentEvent.ApprovalRequired>();
        for (var event : events) {
            if (event instanceof AgentEvent.ApprovalRequired required) unresolved.put(required.approvalId(), required);
            if (event instanceof AgentEvent.ApprovalResolved resolved) unresolved.remove(resolved.approvalId());
        }
        int seq = events.stream().mapToInt(AgentEvent::id).max().orElse(0);
        for (String id : unresolved.keySet()) {
            var status = jdbc.queryForList("SELECT status,decided_at FROM approvals WHERE id=? AND run_id=? AND user_id=? AND status<>'PENDING'", id, runId, owner);
            if (!status.isEmpty()) {
                var row = status.get(0);
                String decision = switch ((String) row.get("status")) {
                    case "APPROVED" -> "APPROVE";
                    case "REJECTED" -> "REJECT";
                    default -> "TIMEOUT";
                };
                var decided = row.get("decided_at");
                events.add(new AgentEvent.ApprovalResolved("approval.resolved", ++seq, runId,
                        decided instanceof java.sql.Timestamp time ? time.toInstant() : java.time.Instant.now(), id, decision));
            }
        }
        return List.copyOf(events);
    }
}
