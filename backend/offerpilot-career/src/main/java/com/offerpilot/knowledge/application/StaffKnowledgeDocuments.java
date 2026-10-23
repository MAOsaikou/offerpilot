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
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

/** Staff-managed originals, independent of private notes and canonical builtin seeds. */
@Service
public class StaffKnowledgeDocuments {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ResumeObjectStore storage;
    private final DocumentTextReader reader;

    public StaffKnowledgeDocuments(JdbcTemplate jdbc, TransactionTemplate tx, ResumeObjectStore storage, DocumentTextReader reader) {
        this.jdbc = jdbc; this.tx = tx; this.storage = storage; this.reader = reader;
    }
    public record Document(String id, String title, String filename, String mime, long byteSize,
                           String indexStatus, String indexError, int chunkCount, boolean sourceTruncated,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record Page(List<Document> items, long total, int page, int size) {}
    private static final org.springframework.jdbc.core.RowMapper<Document> MAPPER = (r, n) -> new Document(
            r.getString("id"), r.getString("title"), r.getString("filename"), r.getString("mime"), r.getLong("byte_size"),
            r.getString("index_status"), r.getString("index_error"), r.getInt("chunk_count"), r.getBoolean("source_truncated"),
            r.getObject("created_at", OffsetDateTime.class), r.getObject("updated_at", OffsetDateTime.class));
    public Page list(int page, int size) {
        if (page < 1 || size < 1) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "page/size 必须为正整数");
        int bounded = Math.min(size, 100);
        return new Page(jdbc.query("SELECT * FROM staff_knowledge_documents ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                MAPPER, bounded, (long) (page - 1) * bounded),
                jdbc.queryForObject("SELECT count(*) FROM staff_knowledge_documents", Long.class), page, bounded);
    }
    public Document get(String id) {
        var rows = jdbc.query("SELECT * FROM staff_knowledge_documents WHERE id=?", MAPPER, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.KNOWLEDGE_NOTE_NOT_FOUND);
        return rows.get(0);
    }
    public Document upload(String staff, String filename, String declaredMime, byte[] bytes) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        int extension = name.lastIndexOf('.');
        if (extension >= 0) name = KnowledgeNoteSearch.bounded(name.substring(0, extension), 240) + name.substring(extension);
        name = KnowledgeNoteSearch.bounded(name, 255);
        var text = reader.read(name, declaredMime, bytes);
        String title = KnowledgeNoteSearch.bounded(name.substring(0, name.lastIndexOf('.')).strip(), 120);
        if (title.isBlank()) title = "上传资料";
        String content = KnowledgeNoteSearch.bounded(text.content(), 30000);
        boolean truncated = text.content().codePointCount(0, text.content().length()) > 30000;
        String file = IdGenerator.next("file"), id = IdGenerator.next("staffdoc");
        String key = "staff-knowledge/" + staff + "/" + file, receipt = IdGenerator.next("cleanup");
        jdbc.update("INSERT INTO account_data_cleanup(id,kind,bucket,object_key,next_attempt_at) VALUES (?,'OBJECT',?,?,now()+interval '1 hour')",
                receipt, storage.bucket(), key);
        String finalName = name, finalTitle = title;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return tx.execute(status -> {
                // Serialize with live role changes and account removal, just as private upload locks its owner.
                if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' AND 'STAFF'=ANY(roles) FOR SHARE", String.class, staff).isEmpty())
                    throw new BusinessException(ErrorCode.FORBIDDEN);
                if (jdbc.queryForList("SELECT id FROM account_data_cleanup WHERE id=? FOR UPDATE", String.class, receipt).isEmpty())
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上传等待超时，请重新上传");
                try { storage.put(key, bytes, text.mime()); }
                catch (RuntimeException ex) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "资料文件存储暂不可用，请稍后重试"); }
                jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,'STAFF_KB_DOCUMENT',?,?,?,?,?,?)",
                        file, staff, storage.bucket(), key, finalName, text.mime(), bytes.length, hash);
                jdbc.update("""
                        INSERT INTO staff_knowledge_documents(id,title,filename,file_id,mime,byte_size,content,source_truncated,created_by)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """, id, finalTitle, finalName, file, text.mime(), bytes.length, content, truncated, staff);
                jdbc.update("DELETE FROM account_data_cleanup WHERE id=?", receipt);
                return get(id);
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
    public Document reindex(String id) {
        return tx.execute(status -> {
            if (jdbc.update("""
                    UPDATE staff_knowledge_documents SET index_status='QUEUED',index_error=NULL,chunk_count=0,
                        revision=revision+1,index_attempts=0,next_attempt_at=now(),updated_at=now() WHERE id=?
                    """, id) == 0) throw new BusinessException(ErrorCode.KNOWLEDGE_NOTE_NOT_FOUND);
            jdbc.update("DELETE FROM staff_knowledge_chunks WHERE document_id=?", id);
            return get(id);
        });
    }
    public void delete(String id) {
        tx.executeWithoutResult(status -> {
            var files = jdbc.queryForList("SELECT file_id FROM staff_knowledge_documents WHERE id=? FOR UPDATE", String.class, id);
            if (files.isEmpty()) throw new BusinessException(ErrorCode.KNOWLEDGE_NOTE_NOT_FOUND);
            String file = files.get(0);
            jdbc.update("INSERT INTO account_data_cleanup(id,kind,bucket,object_key) SELECT ?,'OBJECT',bucket,object_key FROM files WHERE id=?",
                    IdGenerator.next("cleanup"), file);
            jdbc.update("DELETE FROM staff_knowledge_documents WHERE id=?", id);
            jdbc.update("DELETE FROM files WHERE id=? AND purpose='STAFF_KB_DOCUMENT'", file);
        });
    }
}
