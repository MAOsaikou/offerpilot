package com.offerpilot.generation;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.job.application.JobMatchService;
import com.offerpilot.resume.application.ResumeDiagnosisService;
import com.offerpilot.resume.application.ResumeOptimizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Durable non-chat generations; legacy RUNNING records remain visible for old clients. */
@Service
public class GenerationTaskService {
    public static final Set<String> KINDS = Set.of("JOB_MATCH", "RESUME_OPTIMIZE", "RESUME_DIAGNOSIS");

    public record Task(String id, String kind, String status, String title, String href,
                       String createdAt, String modelTier) {}

    public record Start(String kind, String entityId, String requestId, com.fasterxml.jackson.databind.JsonNode input) {}
    public record BackgroundTask(String id, String kind, String entityId, String status, String title, String href,
            String stage, String resultId, String error, String createdAt, String updatedAt, String startedAt, String finishedAt) {}
    private final GenerationWork work;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
            4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32),
            runnable -> { var thread = new Thread(runnable, "generation-worker"); thread.setDaemon(true); return thread; },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final java.util.Map<String, java.util.concurrent.FutureTask<Void>> running = new java.util.concurrent.ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final JobMatchService matches;
    private final ResumeOptimizationService optimizations;
    private final ResumeDiagnosisService diagnoses;

    public GenerationTaskService(JdbcTemplate jdbc, JobMatchService matches,
                                 ResumeOptimizationService optimizations, ResumeDiagnosisService diagnoses, GenerationWork work,
                                 org.springframework.transaction.support.TransactionTemplate tx) {
        this.work=work; this.tx=tx;
        this.jdbc = jdbc;
        this.matches = matches;
        this.optimizations = optimizations;
        this.diagnoses = diagnoses;
    }

    public List<Task> listRunning(String owner) {
        var tasks = new ArrayList<Task>();
        tasks.addAll(jdbc.query("""
                SELECT id, job_id, created_at, report->>'modelTier' AS model_tier
                FROM job_matches d WHERE user_id=? AND status='RUNNING' AND NOT EXISTS (SELECT 1 FROM generation_tasks t WHERE t.user_id=d.user_id AND t.request_id=d.request_id)
                ORDER BY created_at DESC LIMIT 10
                """, (rs, n) -> new Task(rs.getString("id"), "JOB_MATCH", "RUNNING",
                "岗位匹配分析", "/jobs/" + rs.getString("job_id"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("model_tier")), owner));
        tasks.addAll(jdbc.query("""
                SELECT id, resume_id, created_at, model_tier FROM resume_optimizations
                WHERE user_id=? AND status='RUNNING' AND NOT EXISTS (SELECT 1 FROM generation_tasks t WHERE t.user_id=resume_optimizations.user_id AND t.request_id=resume_optimizations.request_id) ORDER BY created_at DESC LIMIT 10
                """, (rs, n) -> new Task(rs.getString("id"), "RESUME_OPTIMIZE", "RUNNING",
                "简历定向改写", "/resumes/" + rs.getString("resume_id"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("model_tier")), owner));
        tasks.addAll(jdbc.query("""
                SELECT id, resume_id, created_at, model_tier FROM resume_diagnoses
                WHERE user_id=? AND status='RUNNING' AND NOT EXISTS (SELECT 1 FROM generation_tasks t WHERE t.user_id=resume_diagnoses.user_id AND t.request_id=resume_diagnoses.request_id) ORDER BY created_at DESC LIMIT 10
                """, (rs, n) -> new Task(rs.getString("id"), "RESUME_DIAGNOSIS", "RUNNING",
                "简历诊断", "/resumes/" + rs.getString("resume_id"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("model_tier")), owner));
        tasks.sort(Comparator.comparing(Task::createdAt).reversed());
        return List.copyOf(tasks.stream().limit(15).toList());
    }

    /** Cancel a RUNNING task owned by the caller; finished tasks are idempotent no-ops. */
    public void cancel(String owner, String kind, String id) {
        if (id == null || id.isBlank() || id.length() > 80) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "任务标识无效");
        }
        String normalized = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的任务类型");
        }
        switch (normalized) {
            case "JOB_MATCH" -> matches.cancel(owner, id);
            case "RESUME_OPTIMIZE" -> optimizations.cancel(owner, id);
            case "RESUME_DIAGNOSIS" -> diagnoses.cancel(owner, id);
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的任务类型");
        }
    }
    @jakarta.annotation.PostConstruct
    public void recoverInterrupted() {
        tx.executeWithoutResult(status -> {
            for (String table : List.of("resume_diagnoses", "resume_optimizations", "job_matches"))
                jdbc.update("UPDATE " + table + " d SET status='FAILED',updated_at=now() WHERE d.status='RUNNING' AND EXISTS "
                        + "(SELECT 1 FROM generation_tasks t WHERE t.user_id=d.user_id AND t.request_id=d.request_id AND t.status IN ('QUEUED','RUNNING'))");
            jdbc.update("UPDATE generation_tasks SET status='FAILED',stage='任务已中断',error='服务重启中断了任务，请重新发起；已有结果可从历史记录查看',updated_at=now(),finished_at=now() WHERE status IN ('QUEUED','RUNNING')");
        });
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    public BackgroundTask start(String owner, Start body) {
        work.validate(owner, body);
        String hash = fingerprint(body);
        String id = tx.execute(status -> {
            if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE", String.class, owner).isEmpty())
                throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            var previous=jdbc.queryForList("SELECT id,request_hash FROM generation_tasks WHERE user_id=? AND request_id=?",owner,body.requestId());
            if (!previous.isEmpty()) {
                if (!hash.equals(previous.get(0).get("request_hash"))) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"请求标识不能用于不同任务");
                return previous.get(0).get("id").toString();
            }
            if (jdbc.queryForObject("SELECT count(*) FROM generation_tasks WHERE user_id=? AND kind=? AND entity_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,owner,body.kind(),body.entityId())>0)
                throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT,"此对象已有后台任务，请等待完成或取消");
            if (jdbc.queryForObject("SELECT count(*) FROM generation_tasks WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,owner)>=5)
                throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT,"已有五项后台任务，请等待完成或取消");
            String next=com.offerpilot.common.id.IdGenerator.next("gen");
            String resume=body.kind().startsWith("RESUME_")?body.entityId():body.kind().equals("JOB_MATCH")?body.input().path("resumeId").asText():null;
            String version=resume==null?null:body.input().path("versionId").asText();
            jdbc.update("INSERT INTO generation_tasks(id,user_id,kind,entity_id,resume_id,version_id,interview_id,request_id,request_hash,status,stage,title,href) VALUES (?,?,?,?,?,?,?,?,?,'QUEUED','等待后台执行',?,?)",
                    next,owner,body.kind(),body.entityId(),resume,version,body.kind().startsWith("INTERVIEW_")?body.entityId():null,
                    body.requestId(),hash,work.title(body.kind()),work.href(body)+(work.href(body).contains("?")?"&":"?")+"taskId="+next);
            return next;
        });
        // Future is registered before submission so cancellation cannot miss a just-started worker.
        if ("QUEUED".equals(get(owner,id).status())) {
            var future=new java.util.concurrent.FutureTask<Void>(() -> { execute(owner,id,body); return null; }) {
                @Override protected void done() { running.remove(id,this); }
            };
            if (running.putIfAbsent(id,future)==null) {
                try { executor.execute(future); }
                catch (java.util.concurrent.RejectedExecutionException ex) {
                    running.remove(id,future);
                    fail(owner,id,"后台队列已满，请稍后重新发起");
                }
            }
        }
        return get(owner,id);
    }

    private String fingerprint(Start body) {
        try {
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
            var canonical=mapper.readValue(mapper.writeValueAsString(body),Object.class);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void execute(String owner,String id,Start body) {
        if (jdbc.update("UPDATE generation_tasks SET status='RUNNING',stage=?,started_at=now(),updated_at=now() WHERE id=? AND user_id=? AND status='QUEUED'",
                "正在执行"+work.title(body.kind())+"并保存结果",id,owner)!=1) return;
        try {
            var result=com.offerpilot.common.artifact.BackgroundWork.run(() -> active(owner,id), () ->
                    com.offerpilot.llm.quota.LlmUserContext.asUser(owner, () -> work.execute(owner,body,reference -> {
                        if (jdbc.update("UPDATE generation_tasks SET result_id=?,updated_at=now() WHERE id=? AND user_id=? AND status='RUNNING'",reference,id,owner)!=1) {
                            cancelDomain(owner,body.kind(),reference);
                            throw new BusinessException(ErrorCode.GENERATION_CANCELLED);
                        }
                    })));
            if (result==null) throw new IllegalStateException("Empty generation result");
            String resultId=result.hasNonNull("reportId")?result.path("reportId").asText():result.hasNonNull("id")?result.path("id").asText():body.kind().equals("CAREER_ASSESSMENT")?owner:id;
            jdbc.update("UPDATE generation_tasks SET status='SUCCEEDED',stage='结果已保存',result_id=?,result=?::jsonb,updated_at=now(),finished_at=now() WHERE id=? AND user_id=? AND status='RUNNING'",
                    resultId,result.toString(),id,owner);
        } catch (RuntimeException ex) {
            // Never expose provider exceptions, prompts or credentials through a task error.
            if (ex instanceof BusinessException b && b.errorCode()==ErrorCode.GENERATION_CANCELLED)
                jdbc.update("UPDATE generation_tasks SET status='CANCELLED',stage='已取消',updated_at=now(),finished_at=now() WHERE id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",id,owner);
            else fail(owner,id,ex instanceof BusinessException b?b.getMessage():"后台生成失败，请重试；已有结果可从历史记录查看");
        }
    }
    private boolean active(String owner,String id) {
        return jdbc.queryForObject("SELECT count(*) FROM generation_tasks WHERE id=? AND user_id=? AND status='RUNNING'",Integer.class,id,owner)==1;
    }
    private void fail(String owner,String id,String error) {
        jdbc.update("UPDATE generation_tasks SET status='FAILED',stage='任务失败',error=?,updated_at=now(),finished_at=now() WHERE id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",error,id,owner);
    }
    private final org.springframework.jdbc.core.RowMapper<BackgroundTask> mapper=(rs,n) -> new BackgroundTask(
            rs.getString("id"),rs.getString("kind"),rs.getString("entity_id"),rs.getString("status"),rs.getString("title"),rs.getString("href"),
            rs.getString("stage"),rs.getString("result_id"),rs.getString("error"),stamp(rs,"created_at"),stamp(rs,"updated_at"),stamp(rs,"started_at"),stamp(rs,"finished_at"));
    private static String stamp(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        var value=rs.getTimestamp(column); return value==null?null:value.toInstant().toString();
    }
    public BackgroundTask get(String owner,String id) {
        var rows=jdbc.query("SELECT * FROM generation_tasks WHERE id=? AND user_id=?",mapper,id,owner);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.ROUTE_NOT_FOUND,"任务不存在或无权访问");
        return rows.get(0);
    }
    public List<BackgroundTask> recent(String owner,String kind,String entity) {
        return jdbc.query("SELECT * FROM generation_tasks WHERE user_id=? AND (?::text IS NULL OR kind=?) AND (?::text IS NULL OR entity_id=?) ORDER BY CASE WHEN status IN ('QUEUED','RUNNING') THEN 0 ELSE 1 END,created_at DESC,id DESC LIMIT 50",mapper,owner,kind,kind,entity,entity);
    }
    public com.fasterxml.jackson.databind.JsonNode result(String owner,String id) {
        var task=get(owner,id);
        if (!"SUCCEEDED".equals(task.status())) throw new BusinessException(ErrorCode.RUN_ALREADY_FINISHED,"任务尚无成功结果");
        var rows=jdbc.queryForList("SELECT result::text FROM generation_tasks WHERE id=? AND user_id=?",String.class,id,owner);
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.get(0)); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public void cancelBackground(String owner,String id) {
        var task=get(owner,id);
        if (jdbc.update("UPDATE generation_tasks SET status='CANCELLED',stage='已取消',updated_at=now(),finished_at=now() WHERE id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",id,owner)!=1) return;
        var future=running.get(id);
        if (future!=null) { future.cancel(true); executor.remove(future); }
        if (task.resultId()!=null) cancelDomain(owner,task.kind(),task.resultId());
    }
    private void cancelDomain(String owner,String kind,String reference) {
        if (KINDS.contains(kind)) cancel(owner,kind,reference);
    }

}
