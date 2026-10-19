package com.offerpilot.knowledge.application;

import com.offerpilot.common.document.DocumentTextReader;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.resume.application.ResumeObjectStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Original files share the resume object store, with owner locking and durable orphan cleanup. */
@Service
public class KnowledgeDocuments {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ResumeObjectStore storage;
    private final DocumentTextReader reader;
    private final KnowledgeNoteService notes;
    public KnowledgeDocuments(JdbcTemplate jdbc, TransactionTemplate tx, ResumeObjectStore storage,
                              DocumentTextReader reader, KnowledgeNoteService notes) {
        this.jdbc = jdbc; this.tx = tx; this.storage = storage; this.reader = reader; this.notes = notes;
    }
    public KnowledgeNoteService.Note upload(String owner, String filename, String declaredMime, byte[] bytes, String category) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        int extension = name.lastIndexOf('.');
        if (extension >= 0) name = KnowledgeNoteSearch.bounded(name.substring(0, extension), 240) + name.substring(extension);
        name = KnowledgeNoteSearch.bounded(name, 255);
        var text = reader.read(name, declaredMime, bytes);
        String selectedCategory = category == null || category.isBlank() ? "技术积累" : category;
        if (!KnowledgeNoteService.CATEGORIES.contains(selectedCategory))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择有效的笔记分类");
        String title = KnowledgeNoteSearch.bounded(name.substring(0, name.lastIndexOf('.')).strip(), 120);
        if (title.isBlank()) title = "上传资料";
        var draft = new KnowledgeNoteService.Draft(title, KnowledgeNoteSearch.bounded(text.content(), 30000), selectedCategory);
        boolean truncated = text.content().codePointCount(0, text.content().length()) > 30000;
        String file = IdGenerator.next("file"), key = "knowledge/" + owner + "/" + file;
        String receipt = IdGenerator.next("cleanup"), finalName = name;
        // Reservation survives a process crash or transaction rollback. Cleanup skips linked objects.
        jdbc.update("INSERT INTO account_data_cleanup(id,kind,bucket,object_key,next_attempt_at) VALUES (?,'OBJECT',?,?,now()+interval '1 hour')",
                receipt, storage.bucket(), key);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return tx.execute(status -> {
                if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR KEY SHARE", String.class, owner).isEmpty())
                    throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                if (jdbc.queryForList("SELECT id FROM account_data_cleanup WHERE id=? FOR UPDATE", String.class, receipt).isEmpty())
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上传等待超时，请重新上传");
                try { storage.put(key, bytes, text.mime()); }
                catch (BusinessException ex) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "资料文件存储暂不可用，请稍后重试"); }
                jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,'KB_DOCUMENT',?,?,?,?,?,?)",
                        file, owner, storage.bucket(), key, finalName, text.mime(), bytes.length, hash);
                var note = notes.create(owner, draft);
                jdbc.update("UPDATE knowledge_notes SET source_file_id=?,source_filename=?,source_truncated=? WHERE id=? AND user_id=?",
                        file, finalName, truncated, note.id(), owner);
                jdbc.update("DELETE FROM account_data_cleanup WHERE id=?", receipt);
                return notes.get(owner, note.id());
            });
        } catch (Exception ex) {
            try {
                storage.remove(storage.bucket(), key);
                jdbc.update("DELETE FROM account_data_cleanup WHERE id=?", receipt);
            } catch (RuntimeException cleanupFailure) {
                jdbc.update("UPDATE account_data_cleanup SET next_attempt_at=now() WHERE id=?", receipt);
            }
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(ex);
        }
    }
    public record Original(String filename, String mime, byte[] bytes) {}
    public Original read(String owner, String note) {
        var files = jdbc.queryForList("""
                SELECT f.name,f.mime,f.bucket,f.object_key FROM knowledge_notes n JOIN files f ON f.id=n.source_file_id
                WHERE n.id=? AND n.user_id=? AND f.user_id=? AND f.purpose='KB_DOCUMENT'
                """, note, owner, owner);
        if (files.isEmpty()) throw new BusinessException(ErrorCode.KNOWLEDGE_NOTE_NOT_FOUND);
        var file = files.get(0);
        try {
            return new Original((String) file.get("name"), (String) file.get("mime"),
                    storage.get((String) file.get("bucket"), (String) file.get("object_key")));
        } catch (BusinessException ex) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "无法读取资料原文件，请稍后重试"); }
    }
}
