package com.offerpilot.notification.web;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.notification.application.Notifications;
import com.offerpilot.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class NotificationControllerTest {
    @Test void authPrecedesEveryInboxOperation() throws Exception {
        var users=mock(CurrentUser.class);var notices=mock(Notifications.class);
        when(users.require(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));when(users.require("Bearer token")).thenReturn("trusted");
        var mvc=MockMvcBuilders.standaloneSetup(new NotificationController(notices,users)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());mvc.perform(get("/api/v1/notifications/unread-count")).andExpect(status().isUnauthorized());mvc.perform(put("/api/v1/notifications/read-all")).andExpect(status().isUnauthorized());mvc.perform(put("/api/v1/notifications/x/read")).andExpect(status().isUnauthorized());verifyNoInteractions(notices);
        mvc.perform(get("/api/v1/notifications").header("Authorization","Bearer token").param("userId","forged")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(notices).list("trusted",1,20);
        mvc.perform(put("/api/v1/notifications/n/read").header("Authorization","Bearer token")).andExpect(status().isOk());verify(notices).read("trusted","n");
    }
}
