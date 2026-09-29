package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.*;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.llm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Owns private version-bound reports. All model IO runs outside short database transactions. */
@Service
public class ResumeDiagnosisService {
    public record Work(String id, String owner, String resumeId, ResumeService.Version version, ModelTier tier, JsonNode completed) {}
    private final ResumeService resumes;
    private final ResumeModelSettings settings;
    private final ModelGateway gateway;
    private final ResumeDiagnoser diagnoser;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Map<String, Sinks.One<Boolean>> cancellations = new ConcurrentHashMap<>();
    public ResumeDiagnosisService(ResumeService resumes, ResumeModelSettings settings, ModelGateway gateway,
            ResumeDiagnoser diagnoser, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.resumes=resumes; this.settings=settings; this.gateway=gateway; this.diagnoser=diagnoser;
        this.jdbc=jdbc; this.tx=tx; this.json=json;
    }
    public Work begin(String owner, String resumeId, String versionId, String requestId) {
        if (requestId == null || !requestId.matches("[a-zA-Z0-9-]{16,80}")) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var version = resumes.version(owner, resumeId, versionId);
        ModelTier tier = ModelTier.valueOf(settings.selection(owner).textTier());
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, owner);
            expire(owner);
            var previous = jdbc.queryForList("SELECT id,version_id,status,report FROM resume_diagnoses WHERE user_id=? AND request_id=?", owner, requestId);
            if (!previous.isEmpty()) {
                var row=previous.get(0);
                if (!versionId.equals(row.get("version_id"))) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求标识不能用于不同简历版本");
                if ("SUCCEEDED".equals(row.get("status"))) return new Work(row.get("id").toString(), owner, resumeId, version, tier, parse(row.get("report").toString()));
                throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT, "本次请求正在处理或已结束，请刷新报告；重试需使用新的请求标识");
            }
            if (jdbc.queryForObject("SELECT count(*) FROM resume_diagnoses WHERE user_id=? AND status='RUNNING'", Integer.class, owner) > 0)
                throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT, "已有简历诊断进行中，请等待完成或取消");
            gateway.requireAvailable(tier, false);
            String id=IdGenerator.next("diag");
            jdbc.update("INSERT INTO resume_diagnoses(id,user_id,resume_id,version_id,request_id,status,model_tier) VALUES (?,?,?,?,?,'RUNNING',?)", id, owner, resumeId, versionId, requestId, tier.name());
            return new Work(id, owner, resumeId, version, tier, null);
        });
    }
    public Mono<JsonNode> execute(Work work) {
        if (work.completed() != null) return Mono.just(work.completed());
        return Mono.defer(() -> {
            var cancellation = Sinks.<Boolean>one();
            if (cancellations.putIfAbsent(work.id(), cancellation) != null) return Mono.error(new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT));
            return Mono.defer(() -> {
                if (!"RUNNING".equals(get(work.owner(), work.id()).path("status").asText())) return Mono.error(cancelled());
                return diagnoser.diagnose(work.version().structured(), work.tier());
            }).takeUntilOther(cancellation.asMono()).switchIfEmpty(Mono.error(cancelled()))
                .publishOn(Schedulers.boundedElastic()).<JsonNode>map(report -> {
                    report.put("reportId", work.id()).put("resumeId", work.resumeId()).put("versionId", work.version().id())
                        .put("versionNo", work.version().versionNo()).put("createdAt", Instant.now().toString());
                    if (report.toString().length() > 30000) throw ResumeDiagnosisValidator.invalid();
                    int saved=jdbc.update("UPDATE resume_diagnoses SET status='SUCCEEDED',report=?::jsonb,updated_at=now() WHERE id=? AND user_id=? AND status='RUNNING'", report.toString(), work.id(), work.owner());
                    if (saved != 1) throw cancelled();
                    return report;
                }).doOnError(error -> {
                    String message=error instanceof BusinessException b ? b.getMessage() : "诊断服务暂时异常，请重试";
                    jdbc.update("UPDATE resume_diagnoses SET status='FAILED',error=?,updated_at=now() WHERE id=? AND user_id=? AND status='RUNNING'", message, work.id(), work.owner());
                }).doFinally(signal -> {
                    cancellations.remove(work.id(), cancellation);
                    if (signal == SignalType.CANCEL) cancel(work.owner(), work.id());
                });
        }).subscribeOn(Schedulers.boundedElastic());
    }
    public Mono<JsonNode> analyze(String owner, String resumeId, String versionId, String requestId) {
        return Mono.fromCallable(() -> begin(owner, resumeId, versionId, requestId)).subscribeOn(Schedulers.boundedElastic()).flatMap(this::execute);
    }
    public JsonNode report(String owner, String resumeId, String versionId, String id) {
        resumes.version(owner, resumeId, versionId);
        var row=get(owner, id);
        if (!resumeId.equals(row.path("resumeId").asText()) || !versionId.equals(row.path("versionId").asText())) throw notFound();
        if (!"SUCCEEDED".equals(row.path("status").asText())) throw new BusinessException(ErrorCode.RESUME_BUSY, "报告尚未完成，请查看诊断状态");
        return row.path("report");
    }
    public JsonNode get(String owner, String id) {
        expire(owner);
        var rows=jdbc.query("SELECT * FROM resume_diagnoses WHERE id=? AND user_id=?", (rs,n) -> {
            ObjectNode row=json.createObjectNode().put("id", rs.getString("id")).put("resumeId", rs.getString("resume_id"))
                .put("versionId", rs.getString("version_id")).put("requestId", rs.getString("request_id")).put("status", rs.getString("status"))
                .put("error", rs.getString("error")).put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            String report=rs.getString("report"); if (report != null) row.set("report", parse(report));
            return (JsonNode)row;
        }, id, owner);
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }
    public List<JsonNode> list(String owner, String resumeId) {
        resumes.get(owner, resumeId); expire(owner);
        return jdbc.query("SELECT d.id,d.version_id,d.request_id,d.status,d.error,d.created_at,v.version_no FROM resume_diagnoses d JOIN resume_versions v ON v.id=d.version_id WHERE d.user_id=? AND d.resume_id=? ORDER BY d.created_at DESC LIMIT 30", (rs,n) -> (JsonNode)json.createObjectNode()
            .put("id",rs.getString("id")).put("versionId",rs.getString("version_id")).put("versionNo",rs.getInt("version_no"))
            .put("requestId",rs.getString("request_id")).put("status",rs.getString("status")).put("error",rs.getString("error"))
            .put("createdAt",rs.getTimestamp("created_at").toInstant().toString()), owner, resumeId);
    }
    public void cancel(String owner, String id) {
        get(owner,id);
        jdbc.update("UPDATE resume_diagnoses SET status='CANCELLED',error='诊断已取消，可重新发起',updated_at=now() WHERE id=? AND user_id=? AND status='RUNNING'", id,owner);
        var signal=cancellations.get(id); if (signal != null) signal.tryEmitValue(true);
    }
    private void expire(String owner) {
        jdbc.update("UPDATE resume_diagnoses SET status='FAILED',error='诊断中断或超时，请重试',updated_at=now() WHERE user_id=? AND status='RUNNING' AND created_at<now()-interval '3 minutes'",owner);
    }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.RESUME_NOT_FOUND, "诊断报告不存在或无权访问"); }
    private static BusinessException cancelled() { return new BusinessException(ErrorCode.RUN_ALREADY_FINISHED, "诊断已取消或过期，请刷新报告状态"); }
}
