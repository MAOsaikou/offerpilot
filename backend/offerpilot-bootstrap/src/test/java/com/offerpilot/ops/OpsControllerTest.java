package com.offerpilot.ops;
import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.error.*;
import com.offerpilot.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class OpsControllerTest {
    @Test void staffGateAndNoStoreProtectStatusAndCleanupKeys() throws Exception {
        var logins=mock(PasswordLoginService.class);var monitor=mock(OpsMonitor.class);var jdbc=mock(JdbcTemplate.class);
        when(logins.requireStaffId("Bearer ordinary")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        when(logins.requireStaffId(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id","cleanup_fixture","kind","OBJECT","attempts",2)));
        var mvc=MockMvcBuilders.standaloneSetup(new OpsController(logins,monitor,jdbc)).setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String path:List.of("status","cleanup")) {
            mvc.perform(get("/api/v1/admin/ops/"+path)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/ops/"+path).header("Authorization","Bearer ordinary")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(jdbc,monitor);
        mvc.perform(get("/api/v1/admin/ops/cleanup").header("Authorization","Bearer staff"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data[0].id").value("cleanup_fixture"));
        verify(jdbc).queryForList("SELECT id,kind,attempts,next_attempt_at,created_at FROM account_data_cleanup ORDER BY created_at LIMIT 100");
    }
}
