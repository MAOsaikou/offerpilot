package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.Map;

@Service
public class ResumePatchService {
    public record Batch(String batchId, String resumeId, String resumeVersionId, JsonNode patches,
                        Map<String, Integer> stats, String appliedVersionId, Instant createdAt) {}
    public record Applied(String batchId, ResumeService.Version version) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ResumeService resumes;
    public ResumePatchService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, ResumeService resumes) {
        this.jdbc = jdbc; this.tx = tx; this.json = json; this.resumes = resumes;
    }

    /** Trusted application entry point for a future optimizer; never writes a resume version. */
    public Batch store(String owner, String resumeId, String versionId, JsonNode patches) {
        var version = resumes.version(owner, resumeId, versionId);
        JsonNode validated = ResumePatches.validateBatch(version.structured(), patches);
        String batchId = IdGenerator.next("pb");
        jdbc.update("INSERT INTO resume_patch_batches(id,user_id,resume_id,resume_version_id,patches) VALUES (?,?,?,?,?::jsonb)",
            batchId, owner, resumeId, versionId, validated.toString());
        return get(owner, resumeId, versionId, batchId);
    }
    public Batch get(String owner, String resumeId, String versionId, String batchId) {
        resumes.version(owner, resumeId, versionId);
        var rows = jdbc.query("SELECT * FROM resume_patch_batches WHERE id=? AND user_id=? AND resume_id=? AND resume_version_id=?",
            (rs, n) -> {
                JsonNode patches = read(rs.getString("patches"));
                return new Batch(rs.getString("id"), rs.getString("resume_id"), rs.getString("resume_version_id"), patches,
                    Map.of("added", 0, "replaced", patches.size(), "removed", 0), rs.getString("applied_version_id"), rs.getTimestamp("created_at").toInstant());
            }, batchId, owner, resumeId, versionId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        return rows.get(0);
    }
    public Applied apply(String owner, String resumeId, String versionId, JsonNode body) {
        var decision = ResumePatches.decision(body);
        String batchId = decision.get("batchId").asText();
        return tx.execute(status -> {
            // Every version writer locks the resume first; no batch/resume lock-order inversion.
            var rows = jdbc.queryForList("SELECT id FROM resumes WHERE id=? AND user_id=? FOR UPDATE", resumeId, owner);
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
            var batch = get(owner, resumeId, versionId, batchId);
            if (batch.appliedVersionId() != null) {
                String saved = jdbc.queryForObject("SELECT decision FROM resume_patch_batches WHERE id=? AND user_id=?", String.class, batchId, owner);
                if (!decision.equals(read(saved))) throw new BusinessException(ErrorCode.RESUME_VERSION_CONFLICT, "此批建议已按另一组决策采纳，请重新生成建议");
                return new Applied(batchId, resumes.version(owner, resumeId, batch.appliedVersionId()));
            }
            var source = resumes.version(owner, resumeId, versionId);
            var updated = ResumePatches.apply(source.structured(), batch.patches(), decision);
            var metadata = json.createObjectNode().put("channel", "patch").put("batchId", batchId);
            var version = resumes.appendVersion(owner, resumeId, versionId, updated, "OPTIMIZED", metadata);
            jdbc.update("UPDATE resume_patch_batches SET applied_version_id=?,decision=?::jsonb WHERE id=? AND user_id=?",
                version.id(), decision.toString(), batchId, owner);
            return new Applied(batchId, version);
        });
    }
    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (java.io.IOException ex) { throw new IllegalStateException("已存补丁数据无法读取", ex); }
    }
}
