package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.*;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.resume.application.ResumeMatchSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;

@Service
public class JobMatchService {
    private final JobService jobs;
    private final ResumeMatchSource resumes;
    private final JobMatcher matcher;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public JobMatchService(JobService jobs,ResumeMatchSource resumes,JobMatcher matcher,JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json) {
        this.jobs=jobs; this.resumes=resumes; this.matcher=matcher; this.jdbc=jdbc; this.tx=tx; this.json=json;
    }
    public JsonNode create(String owner,String jobId,String resumeId,String versionId,String requestId) {
        var resume=resumes.snapshot(owner,resumeId,versionId);
        var job=jobs.get(jobId);
        String id=IdGenerator.next("match");
        String existing=tx.execute(status->{
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",String.class,owner);
            var previous=jdbc.queryForList("SELECT id,status,job_id,resume_version_id FROM job_matches WHERE user_id=? AND request_id=?",owner,requestId);
            if(!previous.isEmpty()) {
                var row=previous.get(0);
                if(!jobId.equals(row.get("job_id")) || !versionId.equals(row.get("resume_version_id"))) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"请求标识不能用于不同的岗位或简历版本");
                if("COMPLETED".equals(row.get("status"))) return row.get("id").toString();
                throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT,"此请求正在处理或已失败，请刷新报告列表；失败后请重新发起分析");
            }
            jdbc.update("UPDATE job_matches SET status='FAILED',updated_at=now() WHERE user_id=? AND status='RUNNING' AND created_at<now()-interval '3 minutes'",owner);
            if(jdbc.queryForObject("SELECT count(*) FROM job_matches WHERE user_id=? AND status='RUNNING'",Integer.class,owner)>0)
                throw new BusinessException(ErrorCode.CONCURRENT_RUN_LIMIT,"已有匹配分析进行中，请等待完成");
            jdbc.update("INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status) VALUES (?,?,?,?,?,'RUNNING')",id,owner,jobId,versionId,requestId);
            return null;
        });
        if(existing!=null) return get(owner,existing);
        try {
            job=jobs.detail(jobId,false);
            JsonNode selectedJob = job;
            ObjectNode report=(ObjectNode)com.offerpilot.llm.quota.LlmUserContext.asUser(owner,
                    () -> matcher.match(selectedJob,resume.structured()));
            report.put("id",id).put("jobId",jobId).put("resumeId",resumeId).put("resumeTitle",resume.title())
                .put("resumeVersionId",versionId).put("resumeVersionNo",resume.versionNo()).put("createdAt",Instant.now().toString());
            report.set("jobSnapshot",job); report.set("resumeSnapshot",resume.structured());
            int saved=jdbc.update("UPDATE job_matches SET status='COMPLETED',report=?::jsonb,updated_at=now() WHERE id=? AND status='RUNNING'",report.toString(),id);
            if(saved!=1) throw new BusinessException(ErrorCode.MODEL_TIMEOUT,"本次匹配已过期，请重新分析");
            return report;
        } catch(RuntimeException ex) {
            jdbc.update("UPDATE job_matches SET status='FAILED',updated_at=now() WHERE id=? AND status='RUNNING'",id);
            throw ex;
        }
    }
    public JsonNode get(String owner,String id) {
        var rows=jdbc.query("SELECT report FROM job_matches WHERE id=? AND user_id=? AND status='COMPLETED'",(rs,n)->parse(rs.getString(1)),id,owner);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.MATCH_NOT_FOUND);
        return rows.get(0);
    }
    public List<JsonNode> list(String owner,String jobId) {
        jdbc.update("UPDATE job_matches SET status='FAILED',updated_at=now() WHERE user_id=? AND status='RUNNING' AND created_at<now()-interval '3 minutes'",owner);
        return jdbc.query("SELECT id,status,resume_version_id,created_at,report->>'resumeTitle' AS title,report->>'score' AS score FROM job_matches WHERE user_id=? AND job_id=? ORDER BY created_at DESC LIMIT 20",(rs,n)-> {
            var row=json.createObjectNode().put("id",rs.getString("id")).put("status",rs.getString("status"))
                .put("resumeVersionId",rs.getString("resume_version_id")).put("createdAt",rs.getTimestamp("created_at").toInstant().toString());
            row.put("resumeTitle",rs.getString("title")); row.put("score",rs.getString("score")); return row;
        },owner,jobId);
    }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch(Exception ex) { throw new IllegalStateException(ex); } }
}
