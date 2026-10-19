package com.offerpilot.knowledge.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Private note persistence. Every resource read/write includes the trusted identity's owner. */
@Service
public class KnowledgeNoteService {
    public static final Set<String> CATEGORIES = Set.of("面试复盘", "技术积累", "项目经历", "求职心得");
    private final JdbcTemplate jdbc;
    private final OccupationEmbeddings embeddings;
    private final boolean indexingEnabled;
    public KnowledgeNoteService(JdbcTemplate jdbc, OccupationEmbeddings embeddings,
            @org.springframework.beans.factory.annotation.Value("${offerpilot.knowledge.notes-embed.enabled:true}") boolean indexingEnabled) {
        this.jdbc = jdbc; this.embeddings = embeddings; this.indexingEnabled = indexingEnabled;
    }
    private String pendingStatus() { return indexingEnabled && embeddings.configured() ? "QUEUED" : "READY"; }

    public record Draft(String title, String content, String category) {}
    public record Note(String id, String title, String content, String category, String createdAt, String updatedAt,
                       String sourceFileId, String sourceFilename, boolean sourceTruncated, String indexStatus, String indexError) {}
    public record Page(List<Note> items, long total, int page, int size) {}
    public record ImportEntry(String sourceId, Draft note) {}
    public record ImportResult(int imported, int alreadyImported) {}

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(String owner, int page, int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 50) invalid("分页范围：page 1-10000，size 1-50");
        long total = jdbc.queryForObject("SELECT count(*) FROM knowledge_notes WHERE user_id=?", Long.class, owner);
        var items = jdbc.query("""
                SELECT * FROM knowledge_notes WHERE user_id=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?
                """, KnowledgeNoteService::map, owner, size, (page - 1) * size);
        return new Page(items, total, page, size);
    }

    public Note get(String owner, String id) {
        var notes = jdbc.query("SELECT * FROM knowledge_notes WHERE user_id=? AND id=?", KnowledgeNoteService::map, owner, id);
        if (notes.isEmpty()) throw new BusinessException(ErrorCode.KNOWLEDGE_NOTE_NOT_FOUND);
        return notes.get(0);
    }

    @Transactional
    public Note create(String owner, Draft draft) {
        validate(draft);
        String id = "knt_" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_notes(id,user_id,title,content,category,tsv,index_status)
                VALUES (?,?,?,?,?,to_tsvector('simple',?),?)
                """, id, owner, draft.title().strip(), draft.content().strip(), draft.category(), fulltext(draft), pendingStatus());
        return get(owner, id);
    }

    @Transactional
    public Note update(String owner, String id, Draft draft) {
        validate(draft);
        int changed = jdbc.update("""
                UPDATE knowledge_notes SET title=?,content=?,category=?,tsv=to_tsvector('simple',?),
                    updated_at=clock_timestamp(),revision=revision+1,embedding=NULL,embedding_next_attempt_at=now(),
                    index_status=?,index_error=NULL,embedding_attempts=0
                WHERE user_id=? AND id=?
                """, draft.title().strip(), draft.content().strip(), draft.category(), fulltext(draft), pendingStatus(), owner, id);
        if (changed == 0) throw new BusinessException(ErrorCode.KNOWLEDGE_NOTE_NOT_FOUND);
        return get(owner, id);
    }

    @Transactional
    public void delete(String owner, String id) {
        // Serialize with account deletion/reset before taking a note lock.
        jdbc.queryForList("SELECT id FROM users WHERE id=? FOR KEY SHARE", String.class, owner);
        var sources = jdbc.queryForList("SELECT source_file_id FROM knowledge_notes WHERE user_id=? AND id=? FOR UPDATE", owner, id);
        if (!sources.isEmpty() && sources.get(0).get("source_file_id") != null) {
            String file = (String) sources.get(0).get("source_file_id");
            jdbc.update("""
                    INSERT INTO account_data_cleanup(id,kind,bucket,object_key)
                    SELECT ?,'OBJECT',bucket,object_key FROM files WHERE id=? AND user_id=? AND purpose='KB_DOCUMENT'
                    """, "cleanup_" + UUID.randomUUID(), file, owner);
            jdbc.update("DELETE FROM files WHERE id=? AND user_id=? AND purpose='KB_DOCUMENT'", file, owner);
        }
        if (jdbc.update("DELETE FROM knowledge_notes WHERE user_id=? AND id=?", owner, id) == 0)
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOTE_NOT_FOUND);
    }

    @Transactional
    public ImportResult importNotes(String owner, List<ImportEntry> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > 50) invalid("每批导入 1-50 条笔记");
        // Serializes same-identity imports, including demo resets; no network calls in this transaction.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, owner);
        int imported = 0;
        for (ImportEntry entry : entries) {
            if (entry == null || entry.sourceId() == null || entry.sourceId().isBlank() || points(entry.sourceId()) > 100)
                invalid("本地笔记标识无效");
            validate(entry.note());
            String hash = fingerprint(entry.note());
            var receipts = jdbc.queryForList("SELECT fingerprint FROM knowledge_note_imports WHERE user_id=? AND source_id=?",
                    String.class, owner, entry.sourceId());
            if (!receipts.isEmpty()) {
                if (!receipts.get(0).equals(hash)) throw new BusinessException(ErrorCode.KNOWLEDGE_IMPORT_CONFLICT);
                continue;
            }
            var note = create(owner, entry.note());
            jdbc.update("INSERT INTO knowledge_note_imports(user_id,source_id,fingerprint,note_id) VALUES (?,?,?,?)",
                    owner, entry.sourceId(), hash, note.id());
            imported++;
        }
        return new ImportResult(imported, entries.size() - imported);
    }

    public static String document(Draft draft) {
        return draft.title().strip() + "\n" + draft.category() + "\n" + draft.content().strip();
    }
    private static String fulltext(Draft draft) { return OccupationDocument.fulltext(document(draft)); }
    private static String fingerprint(Draft draft) {
        try {
            // Length prefixes disambiguate titles/content containing newlines or separators.
            String value = draft.title().length() + ":" + draft.title() + draft.category().length() + ":"
                    + draft.category() + draft.content().length() + ":" + draft.content();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void validate(Draft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || points(draft.title()) > 120
                || draft.content() == null || draft.content().isBlank() || points(draft.content()) > 30000
                || !CATEGORIES.contains(draft.category() == null ? "" : draft.category())) invalid("笔记标题、内容或分类无效");
    }
    private static int points(String text) { return text.codePointCount(0, text.length()); }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
    private static Note map(ResultSet rs, int row) throws SQLException {
        return new Note(rs.getString("id"), rs.getString("title"), rs.getString("content"), rs.getString("category"),
                rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString(),
                rs.getString("source_file_id"), rs.getString("source_filename"), rs.getBoolean("source_truncated"),
                rs.getString("index_status"), rs.getString("index_error"));
    }
}
