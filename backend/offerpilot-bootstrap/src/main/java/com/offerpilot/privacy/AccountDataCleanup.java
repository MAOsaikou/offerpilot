package com.offerpilot.privacy;

import com.offerpilot.llm.quota.LlmUsageStore;
import com.offerpilot.resume.application.ResumeObjectStore;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, idempotent cleanup. Multiple instances serialize each receipt with SKIP LOCKED. */
@Service
public class AccountDataCleanup {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ResumeObjectStore storage;
    private final LlmUsageStore quotas;
    public AccountDataCleanup(JdbcTemplate jdbc, TransactionTemplate tx, ResumeObjectStore storage, LlmUsageStore quotas) {
        this.jdbc = jdbc; this.tx = tx; this.storage = storage; this.quotas = quotas;
    }
    public void clean(String id) {
        tx.executeWithoutResult(status -> {
            var rows = jdbc.queryForList("SELECT * FROM account_data_cleanup WHERE id=? FOR UPDATE SKIP LOCKED", id);
            if (rows.isEmpty()) return;
            var row = rows.get(0);
            try {
                if ("OBJECT".equals(row.get("kind"))) storage.remove((String) row.get("bucket"), (String) row.get("object_key"));
                else quotas.erase((String) row.get("object_key"));
                jdbc.update("DELETE FROM account_data_cleanup WHERE id=?", id);
            } catch (RuntimeException ex) {
                jdbc.update("UPDATE account_data_cleanup SET attempts=attempts+1,next_attempt_at=now()+interval '1 minute' WHERE id=?", id);
                // Receipt id only: storage errors can contain signed URLs or personal filenames.
                LoggerFactory.getLogger(getClass()).warn("Account data cleanup pending receipt={}", id);
            }
        });
    }
    @Scheduled(fixedDelayString = "${offerpilot.privacy.cleanup-delay-ms:60000}")
    public void retryPending() {
        jdbc.queryForList("SELECT id FROM account_data_cleanup WHERE next_attempt_at<=now() ORDER BY next_attempt_at LIMIT 100", String.class)
                .forEach(this::clean);
    }
}
