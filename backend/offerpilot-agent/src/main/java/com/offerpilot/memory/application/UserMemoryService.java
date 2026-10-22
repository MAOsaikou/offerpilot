package com.offerpilot.memory.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.knowledge.application.OccupationDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Agent-owned durable facts. Owner locking serializes cap, dedupe, toggles and account deletion. */
@Service
public class UserMemoryService {
    public static final int CAP = 50;
    public enum Kind { GOAL, LOCATION, SKILL, PREFERENCE, CONSTRAINT, OTHER }
    public enum Source { EXPLICIT, SUGGESTED_ACCEPTED, MANUAL }
    public record Draft(Kind kind, String fact) {}
    public record Memory(String id, Kind kind, String fact, Source source, String sourceConversationId,
                         String sourceMessageId, String createdAt, String updatedAt) {}
    public record Settings(boolean memoryEnabled) {}
    public record Inventory(List<Memory> items, boolean memoryEnabled, int limit) {}
    private final JdbcTemplate jdbc;
    public UserMemoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean enabled(String owner) {
        // Missing preferences means ON; a deleted/disabled owner must never be considered enabled.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM users u LEFT JOIN user_preferences p ON p.user_id=u.id
                WHERE u.id=? AND u.status='ACTIVE' AND coalesce(p.memory_enabled,true))
                """, Boolean.class, owner));
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Inventory list(String owner) {
        return new Inventory(jdbc.query("SELECT * FROM user_memories WHERE user_id=? ORDER BY updated_at DESC,id",
                UserMemoryService::map, owner), enabled(owner), CAP);
    }
    @Transactional
    public Settings settings(String owner, boolean enabled) {
        lock(owner);
        jdbc.update("""
                INSERT INTO user_preferences(user_id,memory_enabled) VALUES (?,?)
                ON CONFLICT(user_id) DO UPDATE SET memory_enabled=excluded.memory_enabled,updated_at=now()
                """, owner, enabled);
        return new Settings(enabled);
    }
    @Transactional
    public Memory add(String owner, Draft draft) {
        lock(owner);
        return insert(owner, draft, Source.MANUAL, null, null);
    }
    @Transactional
    public Memory edit(String owner, String id, Draft draft) {
        validate(draft); lock(owner); get(owner, id);
        if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_memories WHERE user_id=? AND normalized_fact=? AND id<>?)",
                Boolean.class, owner, normalize(draft.fact()), id)) throw conflict("已有相同记忆，请编辑原条目");
        // An edited fact is now user-authored; its old chat provenance no longer supports the text.
        jdbc.update("""
                UPDATE user_memories SET kind=?,fact=?,normalized_fact=?,source='MANUAL',
                source_conversation_id=NULL,source_message_id=NULL,tsv=to_tsvector('simple',?),updated_at=now()
                WHERE user_id=? AND id=?
                """, draft.kind().name(), draft.fact().strip(), normalize(draft.fact()),
                OccupationDocument.fulltext(draft.fact()), owner, id);
        return get(owner, id);
    }
    @Transactional
    public void delete(String owner, String id) {
        lock(owner);
        if (jdbc.update("DELETE FROM user_memories WHERE user_id=? AND id=?", owner, id) == 0) throw missing();
    }
    @Transactional
    public void clear(String owner) { lock(owner); jdbc.update("UPDATE memory_candidates SET status='DELETED' WHERE user_id=?", owner); jdbc.update("DELETE FROM user_memories WHERE user_id=?", owner); }

    /** Stable user-authored facts from an explicit profile save; shares cap, validation, FTS and owner lock. */
    @Transactional
    public void upsertPrefixed(String owner, Kind kind, String prefix, String value) {
        lock(owner);
        if (!enabled(owner)) return;
        if (prefix == null || prefix.isBlank()) throw invalid("记忆前缀不能为空");
        String text = value == null ? "" : value.replaceAll("(?U)\\s+", " ").strip();
        var existing = list(owner).items().stream().filter(m -> m.fact().startsWith(prefix)).toList();
        // Blank means remove the corresponding durable fact, so a cleared field cannot reappear on load.
        if (text.isEmpty()) {
            for (var fact : existing) delete(owner, fact.id());
            return;
        }
        var draft = new Draft(kind, prefix + text);
        validate(draft);
        if (existing.isEmpty()) add(owner, draft);
        else {
            for (int i = 1; i < existing.size(); i++) delete(owner, existing.get(i).id());
            if (!existing.get(0).fact().equals(draft.fact()) || existing.get(0).kind() != kind)
                edit(owner, existing.get(0).id(), draft);
        }
    }

    public boolean contains(String owner, String fact) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_memories WHERE user_id=? AND normalized_fact=?)",
                Boolean.class, owner, normalize(fact)));
    }
    public List<Memory> recall(String owner, String query) {
        if (!enabled(owner)) return List.of();
        String q = OccupationDocument.lexicalQuery(query == null ? "" : query);
        // Reserve one latest GOAL, LOCATION and PREFERENCE; fill remaining slots by FTS relevance/recency.
        // A hard total of five applies even when the user has many goals and locations.
        return jdbc.query("""
                WITH ranked AS (
                  SELECT m.*,row_number() OVER (PARTITION BY kind ORDER BY updated_at DESC,id) AS position,
                    ts_rank_cd(tsv,to_tsquery('simple',?)) AS score
                  FROM user_memories m WHERE user_id=?
                ) SELECT * FROM ranked
                WHERE (kind IN ('GOAL','LOCATION','PREFERENCE') AND position=1) OR score>0
                ORDER BY (kind IN ('GOAL','LOCATION','PREFERENCE') AND position=1) DESC,score DESC,updated_at DESC,id LIMIT 5
                """, UserMemoryService::map, q, owner);
    }

    /** Called after approval only. Re-derive from the persisted USER message, never trust model/card args. */
    @Transactional
    public Memory saveApproved(String owner, String runId, MemoryCandidate.Candidate candidate) {
        return saveApproved(owner, runId, candidate, true);
    }

    /** requireRunning=false is for an approval decided after its chat stream ended; grounding is unchanged. */
    @Transactional
    public Memory saveApproved(String owner, String runId, MemoryCandidate.Candidate candidate, boolean requireRunning) {
        return saveApproved(owner, runId, candidate, requireRunning, false);
    }

    /** Approval may shorten a fact, but may not introduce any text or change its provenance. */
    @Transactional
    public Memory saveApprovedExcerpt(String owner, String runId, MemoryCandidate.Candidate candidate, boolean requireRunning) {
        return saveApproved(owner, runId, candidate, requireRunning, true);
    }

    private Memory saveApproved(String owner, String runId, MemoryCandidate.Candidate candidate, boolean requireRunning, boolean excerpt) {
        lock(owner);
        if (!enabled(owner)) throw conflict("长期记忆已关闭，未保存");
        var sources = jdbc.queryForList("""
                SELECT r.conversation_id,r.user_message_id,m.plain_text FROM agent_runs r
                JOIN messages m ON m.id=r.user_message_id AND m.user_id=r.user_id AND m.role='USER'
                WHERE r.id=? AND r.user_id=? AND (r.status='RUNNING' OR NOT ?) FOR UPDATE OF r
                """, runId, owner, requireRunning);
        if (sources.isEmpty()) throw conflict("运行已结束，未保存记忆");
        var row = sources.get(0);
        var grounded = MemoryCandidate.extract((String) row.get("plain_text"));
        if (!candidate.equals(grounded) && !(excerpt && grounded != null
                && grounded.kind() == candidate.kind() && grounded.source() == candidate.source()
                && grounded.fact().contains(candidate.fact()))) throw invalid("记忆必须来自本轮用户原话");
        return insert(owner, new Draft(candidate.kind(), candidate.fact()), candidate.source(),
                (String) row.get("conversation_id"), (String) row.get("user_message_id"));
    }
    public record Pending(String id, Kind kind, String fact, double confidence, String extractor,
                          String sourceConversationId, String sourceMessageId) {}
    public record MessageSource(String conversationId, String messageId, String text) {}
    public MessageSource extractionSource(String owner, String runId) {
        var rows = jdbc.query("""
                SELECT r.conversation_id,m.id,m.plain_text FROM agent_runs r
                JOIN messages m ON m.id=r.user_message_id AND m.user_id=r.user_id AND m.role='USER'
                WHERE r.id=? AND r.user_id=?
                """, (rs,n) -> new MessageSource(rs.getString(1),rs.getString(2),rs.getString(3)), runId,owner);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<Pending> candidates(String owner) {
        return jdbc.query("""
                SELECT c.* FROM memory_candidates c WHERE user_id=? AND status='PENDING'
                AND NOT EXISTS(SELECT 1 FROM user_memories m WHERE m.user_id=c.user_id AND m.normalized_fact=c.normalized_fact)
                ORDER BY created_at,id
                """, (rs,n) -> new Pending(rs.getString("id"),Kind.valueOf(rs.getString("kind")),rs.getString("fact"),
                rs.getDouble("confidence"),rs.getString("extractor"),rs.getString("source_conversation_id"),rs.getString("source_message_id")),owner);
    }
    @Transactional
    public void suggest(String owner, String runId, MessageSource source, List<AutomaticMemoryExtractor.Fact> facts) {
        lock(owner);
        if (!enabled(owner)) return;
        if (jdbc.queryForList("SELECT id FROM agent_runs WHERE id=? AND user_id=? AND status='RUNNING' FOR UPDATE",String.class,runId,owner).isEmpty()
                || !source.equals(extractionSource(owner,runId))) return;
        int pending = candidates(owner).size();
        for (var fact : facts) {
            if (pending >= CAP) break;
            // Revalidate independently of the provider, including first-person assertion and exact provenance.
            if (AutomaticMemoryExtractor.rules(source.text()).stream().noneMatch(f -> f.fact().equals(fact.fact()) && f.kind()==fact.kind())
                    || !Double.isFinite(fact.confidence()) || fact.confidence()<0 || fact.confidence()>1 || contains(owner,fact.fact())) continue;
            pending += jdbc.update("""
                    INSERT INTO memory_candidates(id,user_id,kind,fact,normalized_fact,confidence,extractor,source_conversation_id,source_message_id)
                    VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,normalized_fact) DO NOTHING
                    """, "mc_"+UUID.randomUUID(),owner,fact.kind().name(),fact.fact(),normalize(fact.fact()),fact.confidence(),fact.extractor(),source.conversationId(),source.messageId());
        }
    }
    @Transactional
    public Memory confirmCandidate(String owner, String id) {
        lock(owner);
        if (!enabled(owner)) throw conflict("长期记忆已关闭，未保存");
        var candidate = candidates(owner).stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        if (candidate == null) {
            var accepted = jdbc.query("""
                    SELECT m.* FROM memory_candidates c JOIN user_memories m ON m.user_id=c.user_id AND m.normalized_fact=c.normalized_fact
                    WHERE c.user_id=? AND c.id=? AND c.status='ACCEPTED'
                    """,UserMemoryService::map,owner,id);
            if (accepted.isEmpty()) throw missing();
            return accepted.get(0);
        }
        var memory = insert(owner,new Draft(candidate.kind(),candidate.fact()),Source.SUGGESTED_ACCEPTED,
                candidate.sourceConversationId(),candidate.sourceMessageId());
        jdbc.update("UPDATE memory_candidates SET status='ACCEPTED' WHERE user_id=? AND id=?",owner,id);
        return memory;
    }
    @Transactional
    public void deleteCandidate(String owner, String id) {
        lock(owner);
        if (jdbc.update("UPDATE memory_candidates SET status='DELETED' WHERE user_id=? AND id=?",owner,id)==0) throw missing();
    }
    private Memory insert(String owner, Draft draft, Source source, String conversation, String message) {
        validate(draft);
        var existing = jdbc.query("SELECT * FROM user_memories WHERE user_id=? AND normalized_fact=?",
                UserMemoryService::map, owner, normalize(draft.fact()));
        if (!existing.isEmpty()) return existing.get(0); // Idempotent add; does not overwrite source/kind.
        if (jdbc.queryForObject("SELECT count(*) FROM user_memories WHERE user_id=?", Integer.class, owner) >= CAP)
            throw conflict("最多保存50条记忆，请先删除旧条目");
        String id = "mem_" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_memories(id,user_id,kind,fact,normalized_fact,source,source_conversation_id,source_message_id,tsv)
                VALUES (?,?,?,?,?,?,?,?,to_tsvector('simple',?))
                """, id, owner, draft.kind().name(), draft.fact().strip(), normalize(draft.fact()), source.name(),
                conversation, message, OccupationDocument.fulltext(draft.fact()));
        return get(owner, id);
    }
    private void lock(String owner) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE", String.class, owner).isEmpty())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    private Memory get(String owner, String id) {
        var result = jdbc.query("SELECT * FROM user_memories WHERE user_id=? AND id=?", UserMemoryService::map, owner, id);
        if (result.isEmpty()) throw missing();
        return result.get(0);
    }
    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("(?U)\\s+", " ").strip().replaceAll("[。.!！]+$", "").strip();
    }
    public static void validate(Draft draft) {
        if (draft == null || draft.kind() == null || draft.fact() == null || draft.fact().isBlank()
                || draft.fact().codePointCount(0, draft.fact().length()) > 200 || normalize(draft.fact()).isBlank()
                || draft.fact().codePoints().anyMatch(c -> Character.isISOControl(c)))
            throw invalid("请选择记忆类型，并填写1–200字的单行事实");
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.MEMORY_CONFLICT, message); }
    private static BusinessException missing() { return new BusinessException(ErrorCode.MEMORY_NOT_FOUND); }
    private static Memory map(ResultSet rs, int row) throws SQLException {
        return new Memory(rs.getString("id"), Kind.valueOf(rs.getString("kind")), rs.getString("fact"),
                Source.valueOf(rs.getString("source")), rs.getString("source_conversation_id"), rs.getString("source_message_id"),
                rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString());
    }
}
