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
