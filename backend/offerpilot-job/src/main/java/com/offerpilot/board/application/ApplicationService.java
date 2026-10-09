package com.offerpilot.board.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.job.application.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

@Service
public class ApplicationService {
    private final JdbcTemplate jdbc;
    private final JobService jobs;
    public ApplicationService(JdbcTemplate jdbc, JobService jobs) { this.jdbc=jdbc; this.jobs=jobs; }
    public record Application(String id, String jobId, String status, String note,
                              String createdAt, String updatedAt, JsonNode job) {}
    private Application view(ResultSet rs, int row) throws SQLException {
        return new Application(rs.getString("id"),rs.getString("job_id"),rs.getString("status"),rs.getString("note"),
            rs.getTimestamp("created_at").toInstant().toString(),rs.getTimestamp("updated_at").toInstant().toString(),
            jobs.get(rs.getString("job_id")));
    }
    @Transactional(readOnly=true)
    public List<Application> list(String owner) {
        return jdbc.query("SELECT * FROM applications WHERE user_id=? ORDER BY created_at DESC,id",this::view,owner);
    }
    @Transactional
    public Application create(String owner, String jobId) {
        jobs.get(jobId); // Validate the existing public job without contacting MCP.
        jdbc.update("INSERT INTO applications(id,user_id,job_id) VALUES (?,?,?) ON CONFLICT(user_id,job_id) DO NOTHING",
            IdGenerator.next("app"),owner,jobId);
        return jdbc.queryForObject("SELECT * FROM applications WHERE user_id=? AND job_id=?",this::view,owner,jobId);
    }
    /** Agent creation commits initial stage and note atomically after approval. */
    @Transactional
    public Application create(String owner, String jobId, String status, String note) {
        Application saved = create(owner, jobId);
        return update(owner, saved.id(), status == null ? "TODO" : status, note);
    }
    @Transactional
    public Application update(String owner, String id, String status, String note) {
        if ((status==null && note==null) || (status!=null && !Set.of("TODO","APPLIED","INTERVIEW","OFFER","CLOSED").contains(status))
                || (note!=null && note.codePointCount(0,note.length())>2000))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var rows=jdbc.query("UPDATE applications SET status=COALESCE(?,status),note=COALESCE(?,note),updated_at=now() WHERE id=? AND user_id=? RETURNING *",
            this::view,status,note,id,owner);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.APPLICATION_NOT_FOUND);
        return rows.get(0);
    }
}
