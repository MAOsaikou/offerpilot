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

@Service
public class ResumeOptimizationService {
    public record Input(String requestId, String jobDescription, String jobId) {
        public Input(String requestId, String jobDescription) { this(requestId,jobDescription,null); }
    }
    public record Work(String id, String owner, String resumeId, ResumeService.Version version,
                       ModelTier tier, String jd, JsonNode completed) {}
    private final com.offerpilot.common.artifact.JobArtifacts artifacts;
    private final ResumeService resumes;
    private final ResumePatchService patches;
    private final ResumeModelSettings settings;
    private final ModelGateway gateway;
    private final ResumeOptimizer optimizer;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Map<String, Sinks.One<Boolean>> cancellations = new ConcurrentHashMap<>();
    public ResumeOptimizationService(ResumeService resumes, ResumePatchService patches, ResumeModelSettings settings,
            ModelGateway gateway, ResumeOptimizer optimizer, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, com.offerpilot.common.artifact.JobArtifacts artifacts) {
        this.resumes=resumes; this.patches=patches; this.settings=settings; this.gateway=gateway;
        this.artifacts=artifacts; this.optimizer=optimizer; this.jdbc=jdbc; this.tx=tx; this.json=json;
    }
    public static Input input(JsonNode body) {
        if (body == null || !body.isObject() || (body.size() != 2 && body.size() != 3) || (body.has("jobId") && !body.path("jobId").isNull() && !body.path("jobId").isTextual()) || (body.size() == 3 && !body.has("jobId")) || !body.path("requestId").isTextual() || !body.path("jobDescription").isTextual())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return new Input(body.get("requestId").asText(), body.get("jobDescription").asText(), body.hasNonNull("jobId") ? body.get("jobId").asText() : null);
    }
    public Work begin(String owner, String resumeId, String versionId, Input input) {
        if (input.requestId() == null || !input.requestId().matches("[a-zA-Z0-9-]{16,80}") || input.jobDescription() == null
                || (input.jobId()!=null && !input.jobId().matches("\\S{1,80}")) || input.jobDescription().isBlank() || input.jobDescription().length() > 20000) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var version=resumes.version(owner,resumeId,versionId);
        if (!"READY".equals(resumes.get(owner,resumeId).parseStatus())) throw new BusinessException(ErrorCode.RESUME_BUSY);
        String jd=input.jobDescription().strip(); ModelTier tier=ModelTier.valueOf(settings.selection(owner).textTier());
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",String.class,owner);
            artifacts.lockJob(owner,input.jobId());
            expire(owner);
            var previous=jdbc.queryForList("SELECT id,version_id,job_description,job_id,status,report FROM resume_optimizations WHERE user_id=? AND request_id=?",owner,input.requestId());
            if (!previous.isEmpty()) {
                var row=previous.get(0);
                if (!versionId.equals(row.get("version_id")) || !jd.equals(row.get("job_description")) || !Objects.equals(input.jobId(),row.get("job_id")))
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,"请求标识不能用于不同简历版本、岗位或岗位内容");
                if ("CANCELLED".equals(row.get("status"))) throw new BusinessException(ErrorCode.GENERATION_CANCELLED);
                if ("SUCCEEDED".equals(row.get("status"))) return new Work(row.get("id").toString(),owner,resumeId,version,tier,jd,parse(row.get("report").toString()));
                throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT,"本次改写正在处理或已结束，请刷新记录；重试使用新的请求标识");
            }
            if (jdbc.queryForObject("SELECT count(*) FROM resume_optimizations WHERE user_id=? AND status='RUNNING'",Integer.class,owner)>0)
                throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT,"已有简历改写进行中，请等待完成或取消");
            gateway.requireAvailable(tier,false);
            gateway.checkQuota(owner,tier);
            String id=IdGenerator.next("opt");
            jdbc.update("INSERT INTO resume_optimizations(id,user_id,resume_id,version_id,request_id,status,model_tier,job_description,job_id) VALUES (?,?,?,?,?,'RUNNING',?,?,?)",
                id,owner,resumeId,versionId,input.requestId(),tier.name(),jd,input.jobId());
            artifacts.linkJob(owner,input.jobId());
            return new Work(id,owner,resumeId,version,tier,jd,null);
        });
    }
    public Mono<JsonNode> execute(Work work) {
        if (work.completed()!=null) return Mono.just(work.completed());
        return Mono.defer(() -> {
            var cancellation=Sinks.<Boolean>one();
            if (cancellations.putIfAbsent(work.id(),cancellation)!=null) return Mono.error(new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT));
            return Mono.defer(() -> {
                if (!"RUNNING".equals(get(work.owner(),work.id()).path("status").asText())) return Mono.error(finished(work));
                return optimizer.optimize(work.version().structured(),work.jd(),work.tier());
            }).takeUntilOther(cancellation.asMono()).switchIfEmpty(Mono.defer(() -> Mono.error(finished(work))))
                .publishOn(Schedulers.boundedElastic()).<JsonNode>map(report -> complete(work,report))
                .onErrorMap(error -> error instanceof BusinessException ? error : new BusinessException(ErrorCode.DATABASE_UNAVAILABLE))
                .doOnError(error -> jdbc.update("UPDATE resume_optimizations SET status='FAILED',error=?,updated_at=now() WHERE id=? AND user_id=? AND status='RUNNING'",
                    ((BusinessException)error).getMessage(),work.id(),work.owner()))
                .doFinally(signal -> {
                    cancellations.remove(work.id(),cancellation);
                    if (signal==SignalType.CANCEL) cancel(work.owner(),work.id());
                });
        }).subscribeOn(Schedulers.boundedElastic())
                .contextWrite(context -> com.offerpilot.llm.quota.LlmUserContext.withUser(context, work.owner()));
    }
    private JsonNode complete(Work work,ObjectNode report) {
        return tx.execute(status -> {
            var rows=jdbc.queryForList("SELECT status,created_at FROM resume_optimizations WHERE id=? AND user_id=? FOR UPDATE",work.id(),work.owner());
            if (rows.isEmpty() || !"RUNNING".equals(rows.get(0).get("status"))
                    || ((java.sql.Timestamp)rows.get(0).get("created_at")).toInstant().isBefore(Instant.now().minusSeconds(180))) throw finished(work);
            var batch=report.path("patches").isEmpty() ? null : patches.store(work.owner(),work.resumeId(),work.version().id(),report.path("patches"));
            report.put("reportId",work.id()).put("resumeId",work.resumeId()).put("resumeVersionId",work.version().id())
                .put("versionNo",work.version().versionNo()).put("createdAt",Instant.now().toString());
            if (batch==null) report.putNull("batchId"); else report.put("batchId",batch.batchId());
            report.putObject("stats").put("added",0).put("replaced",report.path("patches").size()).put("removed",0);
            if (report.toString().length()>30000) throw ResumeOptimizationValidator.invalid();
            jdbc.update("UPDATE resume_optimizations SET status='SUCCEEDED',report=?::jsonb,updated_at=now() WHERE id=? AND user_id=?",report.toString(),work.id(),work.owner());
            return report;
        });
    }
    public JsonNode get(String owner,String id) {
        expire(owner);
        var rows=jdbc.query("SELECT * FROM resume_optimizations WHERE id=? AND user_id=?",(rs,n) -> {
            ObjectNode row=json.createObjectNode().put("id",rs.getString("id")).put("resumeId",rs.getString("resume_id"))
                .put("versionId",rs.getString("version_id")).put("requestId",rs.getString("request_id")).put("status",rs.getString("status"))
                .put("jobId",rs.getString("job_id")).put("jobDescription",rs.getString("job_description")).put("error",rs.getString("error"))
                .put("createdAt",rs.getTimestamp("created_at").toInstant().toString());
            String report=rs.getString("report"); if(report!=null) row.set("report",parse(report));
            return (JsonNode)row;
        },id,owner);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.RESUME_NOT_FOUND,"改写记录不存在或无权访问");
        return rows.get(0);
    }
    public List<JsonNode> list(String owner,String resumeId) {
        resumes.get(owner,resumeId); expire(owner);
        return jdbc.query("SELECT o.id,o.version_id,o.request_id,o.status,o.error,o.created_at,v.version_no FROM resume_optimizations o JOIN resume_versions v ON v.id=o.version_id WHERE o.user_id=? AND o.resume_id=? ORDER BY o.created_at DESC,o.id DESC LIMIT 30",
            (rs,n) -> (JsonNode)json.createObjectNode().put("id",rs.getString("id")).put("versionId",rs.getString("version_id"))
                .put("versionNo",rs.getInt("version_no")).put("requestId",rs.getString("request_id")).put("status",rs.getString("status"))
                .put("error",rs.getString("error")).put("createdAt",rs.getTimestamp("created_at").toInstant().toString()),owner,resumeId);
    }
    public void cancel(String owner,String id) {
        get(owner,id);
        jdbc.update("UPDATE resume_optimizations SET status='CANCELLED',error='改写已取消，可重新发起',updated_at=now() WHERE id=? AND user_id=? AND status='RUNNING'",id,owner);
        var signal=cancellations.get(id); if(signal!=null) signal.tryEmitValue(true);
    }
    private void expire(String owner) {
        jdbc.update("UPDATE resume_optimizations SET status='FAILED',error='改写中断或超时，请重试',updated_at=now() WHERE user_id=? AND status='RUNNING' AND created_at<now()-interval '3 minutes'",owner);
    }
    private JsonNode parse(String value) { try {return json.readTree(value);} catch (Exception ex) {throw new IllegalStateException(ex);} }
    private BusinessException finished(Work work) {
        if ("CANCELLED".equals(get(work.owner(),work.id()).path("status").asText()))
           return new BusinessException(ErrorCode.GENERATION_CANCELLED);
        return new BusinessException(ErrorCode.RUN_ALREADY_FINISHED,"改写已结束或过期，请刷新记录");
    }
}
