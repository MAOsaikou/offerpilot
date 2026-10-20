package com.offerpilot.mcp;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class McpConnectionControllerTest {
    @Test void everyOperationRequiresVerifiedOwnerAndNeverAcceptsUserIdOrDestinationFromBody() throws Exception {
        var users=mock(CurrentUser.class);var connections=mock(McpConnections.class);
        var mvc=MockMvcBuilders.standaloneSetup(new McpConnectionController(users,connections))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.require(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        var requests=java.util.List.of(get("/api/v1/mcp/servers"),
                post("/api/v1/mcp/servers").content("{\"provider\":\"github\"}"),
                patch("/api/v1/mcp/servers/jobs").content("{\"enabled\":false}"),
                post("/api/v1/mcp/servers/github/refresh"),
                post("/api/v1/mcp/servers/github/call").content("{\"tool\":\"get_me\",\"arguments\":{}}"));
        for(var request:requests) mvc.perform(request.contentType("application/json"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(connections);
        when(users.require("Bearer current")).thenReturn("verified-owner");
        mvc.perform(post("/api/v1/mcp/servers").header("Authorization","Bearer current").contentType("application/json")
                .content("{\"provider\":\"github\",\"userId\":\"another-user\",\"url\":\"http://169.254.169.254/\",\"token\":\"untrusted\"}"))
                .andExpect(status().isOk());
        verify(connections).add("verified-owner","github");
        mvc.perform(post("/api/v1/mcp/servers").header("Authorization","Bearer current").contentType("application/json")
                .content("{\"provider\":\"http://127.0.0.1\"}"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(connections);
    }
}
