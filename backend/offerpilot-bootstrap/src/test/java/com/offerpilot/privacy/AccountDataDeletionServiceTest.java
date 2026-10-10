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
    @Test void repeatedLockConflictsReturnRetryableErrorWithoutExternalCleanup() {
        var jdbc=mock(JdbcTemplate.class);
        var tx=mock(TransactionTemplate.class);
        var cleanup=mock(AccountDataCleanup.class);
        when(tx.execute(any())).thenThrow(new PessimisticLockingFailureException("fixture lock conflict"));
        var service=new AccountDataDeletionService(jdbc,tx,cleanup);
        assertThatThrownBy(() -> service.delete("fixture-owner"))
            .isInstanceOfSatisfying(BusinessException.class, e -> org.assertj.core.api.Assertions.assertThat(e.errorCode()).isEqualTo(ErrorCode.DATABASE_UNAVAILABLE));
        verify(tx,times(3)).execute(any());
        verifyNoInteractions(jdbc,cleanup);
    }
}
