package com.offerpilot.board.application;

import com.offerpilot.common.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Same-domain journal. Called only inside the application's or match's write transaction. */
@Service
public class ApplicationTimeline {
    private final JdbcTemplate jdbc;
    public ApplicationTimeline(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String id, String kind, String from, String to, String detail, String actor, String runId) {
        jdbc.update("""
            INSERT INTO application_events(id,application_id,kind,from_status,to_status,detail,actor,run_id)
            VALUES (?,?,?,?,?,?,?,?)
            """, IdGenerator.next("evt"), id, kind, from, to, detail, actor, runId);
    }

    /** Also attaches reports created before the application; repeated favorites never duplicate events. */
    public void linkMatches(String owner, String jobId) {
        jdbc.update("""
            INSERT INTO application_events(id,application_id,kind,detail,artifact_type,artifact_id,actor,created_at)
            SELECT 'evt_match_' || a.id || '_' || m.id,a.id,'ARTIFACT_LINKED',
                   '岗位匹配报告已关联','JOB_MATCH',m.id,'SYSTEM',clock_timestamp()
            FROM applications a JOIN job_matches m ON m.user_id=a.user_id AND m.job_id=a.job_id
            WHERE a.user_id=? AND a.job_id=? AND m.status='COMPLETED'
            ON CONFLICT DO NOTHING
            """, owner, jobId);
    }
}
