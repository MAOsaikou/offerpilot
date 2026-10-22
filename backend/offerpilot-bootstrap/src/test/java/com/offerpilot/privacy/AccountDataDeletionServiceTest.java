package com.offerpilot.privacy;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AccountDataDeletionServiceTest {
    @Test void successfulCommitLeavesRemoteCleanupToTheScheduler() {
        var jdbc=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);var cleanup=mock(AccountDataCleanup.class);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status=mock(org.springframework.transaction.TransactionStatus.class);
        when(tx.getTransactionManager()).thenReturn(manager);when(manager.getTransaction(any())).thenReturn(status);
        when(jdbc.queryForList(startsWith("SELECT is_demo"),eq("fixture-owner")))
                .thenReturn(java.util.List.of(java.util.Map.of("is_demo",false,"staff",false,"status","ACTIVE")));
        when(jdbc.queryForList(startsWith("SELECT bucket"),eq("fixture-owner")))
                .thenReturn(java.util.List.of(java.util.Map.of("bucket","fixture","object_key","private-object")));
        new AccountDataDeletionService(jdbc,tx,cleanup).delete("fixture-owner");
        verify(manager).commit(status);verify(jdbc).update("DELETE FROM users WHERE id=?","fixture-owner");
        verify(jdbc).update(eq("INSERT INTO account_data_cleanup(id,kind,bucket,object_key) VALUES (?,'OBJECT',?,?)"),anyString(),eq("fixture"),eq("private-object"));
        verifyNoInteractions(cleanup);
    }

    @Test void exhaustedDeadlineDoesNotStartAnotherTransactionOrCallStorage() {
        var jdbc=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);var cleanup=mock(AccountDataCleanup.class);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);when(tx.getTransactionManager()).thenReturn(manager);
        var elapsed=new java.util.concurrent.atomic.AtomicLong();
        when(manager.getTransaction(any())).thenAnswer(call -> {
            elapsed.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(26));
            throw new PessimisticLockingFailureException("fixture");
        });
        var service=new AccountDataDeletionService(jdbc,tx,cleanup,elapsed::get);
        assertThatThrownBy(() -> service.delete("fixture-owner")).hasMessageContaining("删除超时");
        verify(manager,times(1)).getTransaction(any());verifyNoInteractions(jdbc,cleanup);
    }
    @Test void queryTimeoutRollsBackAndNeverAttemptsRemoteCleanup() {
        var jdbc=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);var cleanup=mock(AccountDataCleanup.class);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status=mock(org.springframework.transaction.TransactionStatus.class);
        when(tx.getTransactionManager()).thenReturn(manager);when(manager.getTransaction(any())).thenReturn(status);
        doThrow(new org.springframework.dao.QueryTimeoutException("fixture")).when(jdbc).execute(anyString());
        assertThatThrownBy(() -> new AccountDataDeletionService(jdbc,tx,cleanup).delete("fixture-owner")).hasMessageContaining("删除超时");
        verify(manager).rollback(status);verify(manager,never()).commit(any());verifyNoInteractions(cleanup);
    }

    @Test void repeatedLockConflictsReturnRetryableErrorWithoutExternalCleanup() {
        var jdbc=mock(JdbcTemplate.class);
        var tx=mock(TransactionTemplate.class);
        var cleanup=mock(AccountDataCleanup.class);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tx.getTransactionManager()).thenReturn(manager);
        when(manager.getTransaction(any())).thenThrow(new PessimisticLockingFailureException("fixture lock conflict"));
        var service=new AccountDataDeletionService(jdbc,tx,cleanup);
        assertThatThrownBy(() -> service.delete("fixture-owner"))
            .isInstanceOfSatisfying(BusinessException.class, e -> org.assertj.core.api.Assertions.assertThat(e.errorCode()).isEqualTo(ErrorCode.DATABASE_UNAVAILABLE));
        verify(manager,times(3)).getTransaction(argThat(d -> d.getTimeout()>0 && d.getTimeout()<=25));
        verifyNoInteractions(jdbc,cleanup);
    }
}
