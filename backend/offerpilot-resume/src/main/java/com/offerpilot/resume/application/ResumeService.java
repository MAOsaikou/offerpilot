package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Service
public class ResumeService {
    public record Version(String id, int versionNo, String parentVersionId, String source,
                          JsonNode structured, String plainText, JsonNode parseMeta, Instant createdAt) {}
    public record Resume(String id, String title, String parseStatus, int parseProgress, String parseError,
                         String currentVersionId, String fileName, Instant createdAt, Instant updatedAt, Version currentVersion) {}
    public record Page(List<Resume> items, long total, int page, int size) {}
    public record Job(String id, String owner, String token, String bucket, String key, String mime, String textTier, String visionTier) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ResumeObjectStore storage;
    private final ResumeDocumentReader reader;
    private final ResumeModelSettings models;
    public ResumeService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                         ResumeObjectStore storage, ResumeDocumentReader reader, ResumeModelSettings models) {
        this.jdbc = jdbc; this.tx = tx; this.json = json; this.storage = storage; this.reader = reader; this.models = models;
    }
    public Resume upload(String owner, String filename, String title, byte[] bytes) {
        String name = filename == null ? "resume" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank() || name.length() > 255) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件名须为 1–255 个字符");
        String mime = reader.mime(name, bytes);
        if (title != null && title.strip().length() > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "简历名称不能超过 100 个字符");
        String resolvedTitle = title == null || title.isBlank() ? name.replaceFirst("\\.[^.]+$", "") : title.strip();
        if (resolvedTitle.length() > 100) resolvedTitle = resolvedTitle.substring(0, 100);
        if (resolvedTitle.isBlank()) resolvedTitle = "我的简历";
        String id = IdGenerator.next("rs"), fileId = IdGenerator.next("file"), key = "resumes/" + owner + "/" + fileId;
        String finalName = name, finalTitle = resolvedTitle;
        var selection = models.selection(owner);
        storage.put(key, bytes, mime);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,'RESUME',?,?,?,?,?,?)",
                    fileId, owner, storage.bucket(), key, finalName, mime, bytes.length, hash);
                jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,text_model_tier,vision_model_tier) VALUES (?,?,?,?,?,?)",
                    id, owner, finalTitle, fileId, selection.textTier(), selection.visionTier());
            });
        } catch (Exception ex) {
            try { storage.remove(storage.bucket(), key); } catch (Exception cleanup) { ex.addSuppressed(cleanup); }
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(ex);
        }
        return get(owner, id);
    }
    public Page list(String owner, int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 50) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var items = jdbc.query("SELECT r.*,f.name AS file_name FROM resumes r JOIN files f ON f.id=r.source_file_id WHERE r.user_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs, n) -> row(rs, null), owner, size, (page - 1) * size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM resumes WHERE user_id=?", Long.class, owner);
        return new Page(items, total == null ? 0 : total, page, size);
    }
    public Resume get(String owner, String id) {
        var result = jdbc.query("SELECT r.*,f.name AS file_name FROM resumes r JOIN files f ON f.id=r.source_file_id WHERE r.id=? AND r.user_id=?",
            (rs, n) -> row(rs, null), id, owner);
        if (result.isEmpty()) throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        var resume = result.get(0);
        return new Resume(resume.id(), resume.title(), resume.parseStatus(), resume.parseProgress(), resume.parseError(),
            resume.currentVersionId(), resume.fileName(), resume.createdAt(), resume.updatedAt(),
            resume.currentVersionId() == null ? null : version(owner, id, resume.currentVersionId()));
    }
    public List<Version> versions(String owner, String id) {
        owned(owner, id);
        return jdbc.query("SELECT * FROM resume_versions WHERE resume_id=? AND user_id=? ORDER BY version_no DESC",
            (rs, n) -> versionRow(rs), id, owner);
    }
    public Version version(String owner, String id, String versionId) {
        owned(owner, id);
        var result = jdbc.query("SELECT * FROM resume_versions WHERE id=? AND resume_id=? AND user_id=?",
            (rs, n) -> versionRow(rs), versionId, id, owner);
        if (result.isEmpty()) throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        return result.get(0);
    }
    public Resume rename(String owner, String id, String title) {
        if (title == null || title.isBlank() || title.strip().length() > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if (jdbc.update("UPDATE resumes SET title=?,updated_at=now() WHERE id=? AND user_id=?", title.strip(), id, owner) == 0)
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        return get(owner, id);
    }
    public Resume retry(String owner, String id) {
        owned(owner, id);
        var selection = models.selection(owner);
        if (jdbc.update("UPDATE resumes SET parse_status='QUEUED',parse_error=null,parse_progress=0,parse_token=null,text_model_tier=?,vision_model_tier=?,updated_at=now() WHERE id=? AND user_id=? AND parse_status='FAILED'",
            selection.textTier(), selection.visionTier(), id, owner) == 0) throw new BusinessException(ErrorCode.RESUME_BUSY, "仅解析失败的简历可以重新解析");
        return get(owner, id);
    }
    public Resume save(String owner, String id, String baseId, JsonNode structured) {
        appendVersion(owner, id, baseId, structured, "MANUAL_EDIT", json.createObjectNode().put("channel", "manual"));
        return get(owner, id);
    }
    Version appendVersion(String owner, String id, String baseId, JsonNode structured, String source, JsonNode metadata) {
        ResumeStructure.validate(structured);
        return tx.execute(status -> {
            var current = jdbc.queryForList("SELECT current_version_id,parse_status FROM resumes WHERE id=? AND user_id=? FOR UPDATE", id, owner);
            if (current.isEmpty()) throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
            if (!"READY".equals(current.get(0).get("parse_status"))) throw new BusinessException(ErrorCode.RESUME_BUSY);
            if (!baseId.equals(current.get(0).get("current_version_id"))) throw new BusinessException(ErrorCode.RESUME_VERSION_CONFLICT);
            var previous = version(owner, id, baseId);
            String next = IdGenerator.next("rv");
            jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,parent_version_id,source,structured,plain_text,parse_meta) VALUES (?,?,?,?,?,?,?::jsonb,?,?::jsonb)",
                next, id, owner, previous.versionNo() + 1, baseId, source, structured.toString(), plain(structured), metadata.toString());
            jdbc.update("UPDATE resumes SET current_version_id=?,updated_at=now() WHERE id=? AND user_id=?", next, id, owner);
            return version(owner, id, next);
        });
    }
    public Job claim() {
        return tx.execute(status -> {
            // Interrupted tasks become explicit failures rather than silently resending private files.
            jdbc.update("UPDATE resumes SET parse_status='FAILED',parse_error='解析被中断或超时，请重试',parse_token=null,updated_at=now() WHERE parse_status='PARSING' AND parse_started_at < now()-interval '10 minutes'");
            var jobs = jdbc.query("SELECT r.id,r.user_id,r.text_model_tier,r.vision_model_tier,f.bucket,f.object_key,f.mime FROM resumes r JOIN files f ON f.id=r.source_file_id WHERE r.parse_status='QUEUED' ORDER BY r.created_at LIMIT 1 FOR UPDATE OF r SKIP LOCKED",
                (rs,n) -> new Job(rs.getString("id"), rs.getString("user_id"), IdGenerator.next("parse"), rs.getString("bucket"), rs.getString("object_key"), rs.getString("mime"), rs.getString("text_model_tier"), rs.getString("vision_model_tier")));
            if (jobs.isEmpty()) return null;
            Job job = jobs.get(0);
            jdbc.update("UPDATE resumes SET parse_status='PARSING',parse_progress=10,parse_started_at=now(),parse_token=?,updated_at=now() WHERE id=?", job.token(), job.id());
            return job;
        });
    }
    public void progress(Job job) {
        jdbc.update("UPDATE resumes SET parse_progress=50 WHERE id=? AND parse_token=? AND parse_status='PARSING'", job.id(), job.token());
    }
    public void complete(Job job, JsonNode structured, ResumeDocumentReader.Document document) {
        complete(job, structured, json.createObjectNode().put("channel", document.channel()).put("promptVersion", "v2"));
    }
    public void complete(Job job, JsonNode structured, JsonNode metadata) {
        ResumeStructure.validate(structured);
        tx.executeWithoutResult(status -> {
            var rows = jdbc.queryForList("SELECT id FROM resumes WHERE id=? AND parse_token=? AND parse_status='PARSING' FOR UPDATE", job.id(), job.token());
            if (rows.isEmpty()) return;
            String version = IdGenerator.next("rv");
            String meta = metadata.toString();
            jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text,parse_meta) VALUES (?,?,?,1,'UPLOAD',?::jsonb,?,?::jsonb)",
                version, job.id(), job.owner(), structured.toString(), plain(structured), meta);
            jdbc.update("UPDATE resumes SET current_version_id=?,parse_status='READY',parse_progress=100,parse_error=null,parse_token=null,updated_at=now() WHERE id=?", version, job.id());
        });
    }
    public void fail(Job job, String message) {
        jdbc.update("UPDATE resumes SET parse_status='FAILED',parse_error=?,parse_token=null,updated_at=now() WHERE id=? AND parse_token=? AND parse_status='PARSING'", message, job.id(), job.token());
    }
    private void owned(String owner, String id) {
        if (jdbc.queryForList("SELECT id FROM resumes WHERE id=? AND user_id=?", id, owner).isEmpty()) throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
    }
    private Resume row(ResultSet rs, Version version) throws SQLException {
        return new Resume(rs.getString("id"), rs.getString("title"), rs.getString("parse_status"), rs.getInt("parse_progress"),
            rs.getString("parse_error"), rs.getString("current_version_id"), rs.getString("file_name"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), version);
    }
    private Version versionRow(ResultSet rs) throws SQLException {
        try {
            return new Version(rs.getString("id"), rs.getInt("version_no"), rs.getString("parent_version_id"), rs.getString("source"),
                json.readTree(rs.getString("structured")), rs.getString("plain_text"), json.readTree(rs.getString("parse_meta")), rs.getTimestamp("created_at").toInstant());
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    private String plain(JsonNode node) {
        if (node.isTextual()) return node.asText();
        StringBuilder result = new StringBuilder();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getKey().equals("confidence")) result.append(plain(entry.getValue())).append('\n');
        });
        if (node.isArray()) node.forEach(item -> result.append(plain(item)).append('\n'));
        return result.toString().strip();
    }
}
