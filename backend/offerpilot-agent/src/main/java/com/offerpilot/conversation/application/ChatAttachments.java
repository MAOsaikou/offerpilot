package com.offerpilot.conversation.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.resume.application.ResumeObjectStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * AG-02: images attached to a chat turn. Stored as {@code files(purpose=ATTACHMENT)} in the shared object store;
 * only the owner can read or reference them. Deleting the conversation releases them through account_data_cleanup.
 */
@Service
public class ChatAttachments {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_PER_MESSAGE = 3;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ResumeObjectStore storage;

    public ChatAttachments(JdbcTemplate jdbc, TransactionTemplate tx, ResumeObjectStore storage) {
        this.jdbc = jdbc; this.tx = tx; this.storage = storage;
    }

    public record Uploaded(String fileId, String name, String mime, long size) {}
    public record Image(String fileId, String name, String mime, byte[] bytes) {}

    public Uploaded upload(String owner, String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择图片");
        if (bytes.length > MAX_BYTES) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "图片不能超过 5MB");
        String mime = sniff(bytes);
        if (mime == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅支持 PNG、JPG、WebP 图片");
        String name = filename == null ? "image" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "");
        if (name.isBlank()) name = "image";
        if (name.length() > 255) name = name.substring(name.length() - 255);
        String fileId = IdGenerator.next("file"), key = "chat/" + owner + "/" + fileId, finalName = name;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            tx.executeWithoutResult(status -> {
                // Same owner lock as resume upload so account deletion never misses a fresh object.
                if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR KEY SHARE", String.class, owner).isEmpty())
                    throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                storage.put(key, bytes, mime);
                jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,'ATTACHMENT',?,?,?,?,?,?)",
                        fileId, owner, storage.bucket(), key, finalName, mime, bytes.length, hash);
            });
        } catch (Exception ex) {
            try { storage.remove(storage.bucket(), key); } catch (Exception ignored) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Chat attachment orphan key={}", key);
            }
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(ex);
        }
        return new Uploaded(fileId, finalName, mime, bytes.length);
    }

    /** Loads the owner's images for one turn, in request order; unknown or foreign ids are a 400, not a leak. */
    public List<Image> load(String owner, List<String> fileIds) {
        var ids = new LinkedHashSet<>(fileIds);
        if (ids.size() > MAX_PER_MESSAGE) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "每条消息最多附 3 张图片");
        List<Image> images = new ArrayList<>();
        for (String id : ids) images.add(read(owner, id, ErrorCode.VALIDATION_FAILED));
        return images;
    }

    public Image read(String owner, String fileId) { return read(owner, fileId, ErrorCode.ROUTE_NOT_FOUND); }

    private Image read(String owner, String fileId, ErrorCode missing) {
        var rows = jdbc.queryForList("SELECT name,mime,bucket,object_key FROM files WHERE id=? AND user_id=? AND purpose='ATTACHMENT'", fileId, owner);
        if (rows.isEmpty()) throw new BusinessException(missing, "图片不存在或无权访问");
        var row = rows.get(0);
        return new Image(fileId, (String) row.get("name"), (String) row.get("mime"),
                storage.get((String) row.get("bucket"), (String) row.get("object_key")));
    }

    static String sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }
}
