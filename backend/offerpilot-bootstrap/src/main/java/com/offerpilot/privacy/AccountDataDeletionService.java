package com.offerpilot.privacy;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Bootstrap coordinates the cross-domain deletion without reverse business-module dependencies. */
@Service
public class AccountDataDeletionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AccountDataCleanup cleanup;
    public AccountDataDeletionService(JdbcTemplate jdbc, TransactionTemplate tx, AccountDataCleanup cleanup) {
        this.jdbc = jdbc; this.tx = tx; this.cleanup = cleanup;
    }
    private record Deleted(int files, List<String> receipts) {}
    public void delete(String owner) {
        Deleted deleted;
        for (int attempt = 0; ; attempt++) {
            try { deleted = deleteRows(owner); break; }
            catch (PessimisticLockingFailureException ex) {
                // A parser may hold a child row before acquiring its user FK lock.
                // PostgreSQL rolls back the entire losing transaction, including receipts.
                if (attempt >= 2) throw new BusinessException(ErrorCode.DATABASE_UNAVAILABLE, "删除遇到并发写入，请稍后重试");
            } catch (DataAccessException ex) { throw new BusinessException(ErrorCode.DATABASE_UNAVAILABLE); }
        }
        // The account must remain deleted even when external services are unavailable.
        LoggerFactory.getLogger(getClass()).info("Account data deleted subjectHash={} files={} at={}", hash(owner), deleted.files(), java.time.Instant.now());
        for (String receipt : deleted.receipts()) {
            try { cleanup.clean(receipt); }
            catch (RuntimeException ex) { LoggerFactory.getLogger(getClass()).warn("Account data cleanup deferred receipt={}", receipt); }
        }
    }
    private Deleted deleteRows(String owner) {
        return Objects.requireNonNull(tx.execute(status -> {
            var receipts = new ArrayList<String>();
            // Blocks new FK-backed writes before inventory; an authenticated request may already be in flight.
            var accounts = jdbc.queryForList("SELECT is_demo,('STAFF'=ANY(roles)) AS staff,status FROM users WHERE id=? FOR UPDATE", owner);
            if (accounts.isEmpty() || !"ACTIVE".equals(accounts.get(0).get("status"))) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            if (Boolean.TRUE.equals(accounts.get(0).get("is_demo")))
                throw new BusinessException(ErrorCode.DEMO_NOT_ALLOWED, "共享体验账号不能删除全部数据，请由客服使用体验账号重置");
            if (Boolean.TRUE.equals(accounts.get(0).get("staff")))
                throw new BusinessException(ErrorCode.FORBIDDEN, "客服账号不能自助删除，请通过管理员处理，避免丢失管理权限");
            String quota = IdGenerator.next("cleanup");
            jdbc.update("INSERT INTO account_data_cleanup(id,kind,object_key) VALUES (?,'QUOTA',?)", quota, owner);
            receipts.add(quota); // Fence late accounting before potentially slow storage cleanup.
            var objects = jdbc.queryForList("SELECT bucket,object_key FROM files WHERE user_id=?", owner);
            for (var object : objects) {
                String id = IdGenerator.next("cleanup");
                jdbc.update("INSERT INTO account_data_cleanup(id,kind,bucket,object_key) VALUES (?,'OBJECT',?,?)", id, object.get("bucket"), object.get("object_key"));
                receipts.add(id);
            }
            jdbc.update("DELETE FROM users WHERE id=?", owner);
            return new Deleted(objects.size(), List.copyOf(receipts));
        }));
    }
    private static String hash(String owner) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
