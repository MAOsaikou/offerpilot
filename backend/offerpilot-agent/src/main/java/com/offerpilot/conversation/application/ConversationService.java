package com.offerpilot.conversation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Owns conversation transactions; model calls never hold database locks. */
@Service
public class ConversationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ConversationService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public record Conversation(String id, String title, String mode, boolean pinned, Instant createdAt, Instant updatedAt) {}
    public record StoredMessage(String id, String conversationId, String role, String plainText,
                                String runId, String model, String status, Instant createdAt,
                                com.fasterxml.jackson.databind.JsonNode parts, com.fasterxml.jackson.databind.JsonNode usage) {}
    public record Page<T>(List<T> items, int page, int size, long total) {}
    public record MessagePage(List<StoredMessage> items, String nextBefore, boolean hasMore) {}
    public record Turn(String userId, String conversationId, String runId, String messageId,
                       AgentMode mode, Instant startedAt, List<Message> history) {}

    public Page<Conversation> list(String owner, int page, int size) {
        checkPage(page, size);
        var items = jdbc.query("SELECT * FROM conversations WHERE user_id=? ORDER BY pinned DESC,updated_at DESC,id DESC LIMIT ? OFFSET ?",
                this::conversation, owner, size, (page - 1L) * size);
        return new Page<>(items, page, size, jdbc.queryForObject("SELECT count(*) FROM conversations WHERE user_id=?", Long.class, owner));
    }

    public Conversation get(String owner, String id) {
        var result = jdbc.query("SELECT * FROM conversations WHERE id=? AND user_id=?", this::conversation, id, owner);
        if (result.isEmpty()) throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        return result.get(0);
    }

    @Transactional
    public Conversation create(String owner, String title, AgentMode mode) {
        String id = IdGenerator.next("conv");
        jdbc.update("INSERT INTO conversations(id,user_id,title,mode) VALUES (?,?,?,?)", id, owner,
                title == null ? "新对话" : title.strip(), mode == null ? "AUTO" : mode.name());
        return get(owner, id);
    }

    @Transactional
    public Conversation update(String owner, String id, String title, Boolean pinned) {
        lock(owner, id);
        if (title != null) jdbc.update("UPDATE conversations SET title=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", title.strip(), id);
        if (pinned != null) jdbc.update("UPDATE conversations SET pinned=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", pinned, id);
        return get(owner, id);
    }

    @Transactional
    public void delete(String owner, String id) {
        // Keep the same owner -> expired runs -> conversation lock order as begin().
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, owner);
        expire(owner);
        lock(owner, id);
        rejectActive(id);
        jdbc.update("DELETE FROM conversations WHERE id=? AND user_id=?", id, owner);
    }

    @Transactional
    public MessagePage messages(String owner, String id, String before, int size) {
        get(owner, id);
        checkPage(1, size);
        expire(owner);
        long ordinal = Long.MAX_VALUE;
        if (before != null) {
            var positions = jdbc.queryForList("SELECT ordinal FROM messages WHERE id=? AND conversation_id=?", Long.class, before, id);
            if (positions.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "历史消息游标无效");
            ordinal = positions.get(0);
        }
        var items = new ArrayList<>(jdbc.query("SELECT * FROM messages WHERE conversation_id=? AND ordinal<? ORDER BY ordinal DESC LIMIT ?",
                this::message, id, ordinal, size + 1));
        boolean more = items.size() > size;
        if (more) items.remove(items.size() - 1);
        Collections.reverse(items);
        return new MessagePage(items, more ? items.get(0).id() : null, more);
    }

    @Transactional
    public Turn begin(String owner, String conversationId, String content, AgentMode requestedMode, String model) {
        // Serialize run creation per owner, including across application instances.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, owner);
        expire(owner);
        int active = jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE user_id=? AND status='RUNNING'", Integer.class, owner);
        if (active >= 2) throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT);
        Conversation conv;
        if (conversationId == null || conversationId.isBlank()) conv = create(owner, title(content), requestedMode);
        else { lock(owner, conversationId); conv = get(owner, conversationId); }
        rejectActive(conv.id());
        AgentMode mode = requestedMode == null ? AgentMode.valueOf(conv.mode()) : requestedMode;
        var history = context(conv.id());
        String run = IdGenerator.next("run"), userMessage = IdGenerator.next("msg"), assistant = IdGenerator.next("msg");
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO agent_runs(id,user_id,conversation_id,user_message_id,assistant_message_id,mode,goal,status,model,lease_expires_at)
                VALUES (?,?,?,?,?,?,?,'RUNNING',?,?)
                """, run, owner, conv.id(), userMessage, assistant, mode.name(), content, model, Timestamp.from(now.plusSeconds(600)));
        insertMessage(userMessage, conv.id(), owner, "USER", content, run, model, "COMPLETED");
        insertMessage(assistant, conv.id(), owner, "ASSISTANT", "", run, model, "RUNNING");
        jdbc.update("UPDATE conversations SET mode=?,title=CASE WHEN title='新对话' THEN ? ELSE title END,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                mode.name(), title(content), conv.id());
        return new Turn(owner, conv.id(), run, assistant, mode, now, history);
    }

    /** Recent complete pairs only. Failed/cancelled turns never enter future prompts. */
    private List<Message> context(String id) {
        var pairs = jdbc.query("""
                SELECT u.plain_text question,a.plain_text answer FROM agent_runs r
                JOIN messages u ON u.id=r.user_message_id JOIN messages a ON a.id=r.assistant_message_id
                WHERE r.conversation_id=? AND r.status='COMPLETED' ORDER BY u.ordinal DESC LIMIT 10
                """, (rs, row) -> new String[]{rs.getString("question"), rs.getString("answer")}, id);
        List<String[]> kept = new ArrayList<>();
        int chars = 0;
        for (var pair : pairs) {
            int length = pair[0].length() + pair[1].length();
            if (chars + length > 24000) break;
            kept.add(pair); chars += length;
        }
        Collections.reverse(kept);
        List<Message> result = new ArrayList<>();
        for (var pair : kept) { result.add(new UserMessage(pair[0])); result.add(new AssistantMessage(pair[1])); }
        return result;
    }

    /** Checkpoints preserve partial output; terminal compare-and-set prevents late chunks overwriting cancellation. */
    @Transactional
    public boolean checkpoint(Turn turn, String text) {
        var state = jdbc.queryForObject("SELECT status FROM agent_runs WHERE id=? FOR UPDATE", String.class, turn.runId());
        if (!"RUNNING".equals(state)) return false;
        writeMessage(turn.messageId(), text, "RUNNING", null);
        return true;
    }

    @Transactional
    public String finish(Turn turn, String text, String status, Map<String, Object> usage, String error) {
        String current = jdbc.queryForObject("SELECT status FROM agent_runs WHERE id=? FOR UPDATE", String.class, turn.runId());
        if (!"RUNNING".equals(current)) return current;
        writeMessage(turn.messageId(), text, status, usage);
        jdbc.update("UPDATE agent_runs SET status=?,finished_at=CURRENT_TIMESTAMP,error=CAST(? AS jsonb) WHERE id=?",
                status, error == null ? null : encode(Map.of("message", error)), turn.runId());
        return status;
    }

    @Transactional
    public void cancel(String owner, String runId) {
        cancel(owner, runId, null);
    }

    @Transactional
    public void cancel(String owner, String runId, String partial) {
        var ids = jdbc.queryForList("SELECT assistant_message_id FROM agent_runs WHERE id=? AND user_id=? FOR UPDATE", String.class, runId, owner);
        if (ids.isEmpty()) throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        int changed = jdbc.update("UPDATE agent_runs SET status='CANCELLED',finished_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'", runId);
        if (changed > 0) {
            if (partial == null) jdbc.update("UPDATE messages SET status='CANCELLED' WHERE id=?", ids.get(0));
            else writeMessage(ids.get(0), partial, "CANCELLED", null);
        }
    }

    private void expire(String owner) {
        jdbc.queryForList("SELECT id FROM agent_runs WHERE user_id=? AND status='RUNNING' AND lease_expires_at<CURRENT_TIMESTAMP FOR UPDATE", String.class, owner);
        jdbc.update("""
                UPDATE messages SET status='FAILED' WHERE status='RUNNING' AND run_id IN
                (SELECT id FROM agent_runs WHERE user_id=? AND status='RUNNING' AND lease_expires_at<CURRENT_TIMESTAMP)
                """, owner);
        jdbc.update("""
                UPDATE agent_runs SET status='FAILED',finished_at=CURRENT_TIMESTAMP
                WHERE user_id=? AND status='RUNNING' AND lease_expires_at<CURRENT_TIMESTAMP
                """, owner);
    }

    private void lock(String owner, String id) {
        if (jdbc.queryForList("SELECT id FROM conversations WHERE id=? AND user_id=? FOR UPDATE", String.class, id, owner).isEmpty())
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
    }
    private void rejectActive(String id) {
        if (jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE conversation_id=? AND status='RUNNING'", Integer.class, id) > 0)
            throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT, "该会话仍在回复，请等待完成或停止后再操作");
    }
    private void insertMessage(String id, String conv, String owner, String role, String text, String run, String model, String status) {
        jdbc.update("INSERT INTO messages(id,conversation_id,user_id,role,parts,plain_text,run_id,model,status) VALUES (?,?,?,?,CAST(? AS jsonb),?,?,?,?)",
                id, conv, owner, role, parts(text), text, run, model, status);
    }
    private void writeMessage(String id, String text, String status, Map<String, Object> usage) {
        jdbc.update("UPDATE messages SET plain_text=?,parts=CAST(? AS jsonb),status=?,usage=CAST(? AS jsonb) WHERE id=?",
                text, parts(text), status, usage == null ? null : encode(usage), id);
    }
    private String parts(String text) { return encode(List.of(Map.of("type", "text", "text", text))); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private static String title(String text) {
        String compact = text.strip().replaceAll("\\s+", " ");
        int codePoints = Math.min(40, compact.codePointCount(0, compact.length()));
        return compact.substring(0, compact.offsetByCodePoints(0, codePoints));
    }
    private static void checkPage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    private Conversation conversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(rs.getString("id"), rs.getString("title"), rs.getString("mode"), rs.getBoolean("pinned"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private StoredMessage message(ResultSet rs, int row) throws SQLException {
        return new StoredMessage(rs.getString("id"), rs.getString("conversation_id"), rs.getString("role"), rs.getString("plain_text"),
                rs.getString("run_id"), rs.getString("model"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                decode(rs.getString("parts")), decode(rs.getString("usage")));
    }
    private com.fasterxml.jackson.databind.JsonNode decode(String value) {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
