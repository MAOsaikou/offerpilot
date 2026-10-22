package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpConnectionsTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final MockEnvironment env=new MockEnvironment();
    final McpConnections connections=new McpConnections(jdbc,new ObjectMapper(),env);
    @Test void absentTokenIsUnconfiguredAndOnlyDeploymentAddressesAreAccepted() {
        assertThat(connections.destination("github").configured()).isFalse();
        env.setProperty("offerpilot.mcp.github-token","fixture-only");
        assertThat(connections.destination("github").configured()).isTrue();
        assertThat(connections.destination("jobs").endpoint()).isEqualTo("http://localhost:7001/mcp");
        for(String provider:new String[]{"http://127.0.0.1", "https://example.com/mcp", "http://169.254.169.254/", "../jobs", "custom"})
            assertThatThrownBy(()->connections.add("owner",provider)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc);
        for(String url:new String[]{"file:///etc/passwd","http://user:password@example.com/mcp","https://example.com/mcp?key=hidden","https://example.com/#fragment"}) {
            env.setProperty("offerpilot.mcp.search-url",url);
            assertThat(connections.destination("search").configured()).isFalse();
        }
    }
    @Test void githubWritesRemainBlockedEvenWhenListedByRemoteServer() {
        assertThat(McpConnections.callable("github","search_code")).isTrue();
        for(String name:new String[]{"create_issue","push_files","delete_repository","update_pull_request"})
            assertThat(McpConnections.callable("github",name)).isFalse();
        assertThat(McpConnections.callable("search","brave_web_search")).isTrue();
    }
    @Test void refreshPersistsOnlyBoundedMaskedSchemasAndCallBlocksDiscoveredWrites() throws Exception {
        env.setProperty("offerpilot.mcp.github-token","fixture-secret-token");
        var transport=mock(StreamableMcpClient.class);
        var service=spy(connections);
        doReturn(transport).when(service).client(any());
        var json=new ObjectMapper();
        var empty=new McpConnections.Connection("github","GitHub MCP",true,true,true,"NOT_REFRESHED",json.createArrayNode(),null);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<McpConnections.Connection>>any(),eq("owner"),eq("github")))
                .thenReturn(java.util.List.of(empty));
        var discovery=json.readTree("{\"tools\":[{\"name\":\"search_code\",\"description\":\"fixture-secret-token\",\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"push_files\",\"inputSchema\":{\"type\":\"object\"}}]}");
        when(transport.execute(eq("tools/list"),anyMap(),eq(10000L))).thenReturn(discovery);
        service.refresh("owner","github");
        var schemas=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(startsWith("UPDATE user_mcp_servers SET tools="),schemas.capture(),eq("READY"),eq("owner"),eq("github"));
        assertThat(schemas.getValue()).doesNotContain("fixture-secret-token");
        var saved=json.readTree(schemas.getValue());
        assertThat(saved.get(0).path("callable").asBoolean()).isTrue();
        assertThat(saved.get(1).path("callable").asBoolean()).isFalse();
        var ready=new McpConnections.Connection("github","GitHub MCP",true,true,true,"READY",saved,null);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<McpConnections.Connection>>any(),eq("owner"),eq("github")))
                .thenReturn(java.util.List.of(ready));
        clearInvocations(transport);
        assertThatThrownBy(()->service.call("owner","github","push_files",json.createObjectNode())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(transport);
        when(transport.execute(eq("tools/call"),anyMap(),eq(10000L))).thenReturn(json.readTree("{\"text\":\"fixture-secret-token\"}"));
        assertThat(service.call("owner","github","search_code",json.createObjectNode()).toString()).doesNotContain("fixture-secret-token");
        assertThatThrownBy(()->service.call("owner","github","search_code",json.createArrayNode())).isInstanceOf(BusinessException.class);
    }
    @Test void unconfiguredOrDisabledConnectionsMakeNoOutboundRequestsAndFailuresClearCache() throws Exception {
        var service=spy(connections);var transport=mock(StreamableMcpClient.class);
        doReturn(transport).when(service).client(any());
        var json=new ObjectMapper();
        var enabled=new McpConnections.Connection("github","GitHub MCP",true,true,true,"READY",json.createArrayNode(),null);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<McpConnections.Connection>>any(),eq("owner"),eq("github")))
                .thenReturn(java.util.List.of(enabled));
        assertThat(service.refresh("owner","github").status()).isEqualTo("NOT_CONFIGURED");
        verifyNoInteractions(transport);
        env.setProperty("offerpilot.mcp.github-token","fixture-token");
        var disabled=new McpConnections.Connection("github","GitHub MCP",true,false,true,"READY",json.createArrayNode(),null);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<McpConnections.Connection>>any(),eq("owner"),eq("github")))
                .thenReturn(java.util.List.of(disabled));
        assertThat(service.refresh("owner","github").status()).isEqualTo("DISABLED");
        assertThatThrownBy(()->service.requireEnabled("owner","github")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(transport);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<McpConnections.Connection>>any(),eq("owner"),eq("github")))
                .thenReturn(java.util.List.of(enabled));
        when(transport.execute(eq("tools/list"),anyMap(),eq(10000L))).thenThrow(new IllegalStateException("sensitive remote failure"));
        service.refresh("owner","github");
        verify(jdbc).update(startsWith("UPDATE user_mcp_servers SET tools="),eq("[]"),eq("UNAVAILABLE"),eq("owner"),eq("github"));
    }
}
