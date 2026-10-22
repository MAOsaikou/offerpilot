package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.*;
import com.offerpilot.mcp.McpConnections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP, owner authentication, Flyway V44 and cascade lifecycle; no external MCP/model. */
class McpManagementHttpIntegrationTest extends SharedHttpIntegrationTest {
    @Autowired GuestIdentityService guests;
    @Autowired JwtTokenService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired McpConnections connections;
    @LocalServerPort int port;
    @Test void userPreferencesSurviveReloadAreIsolatedAndCascadeWithUser() throws Exception {
        String token=guests.create().accessToken(), other=guests.create().accessToken();
        String owner=guests.require("Bearer "+token);
        assertThat(request("GET","/mcp/servers",null,null).statusCode()).isEqualTo(401);
        assertThat(request("POST","/mcp/servers",token,Map.of("provider","http://169.254.169.254/")).statusCode()).isEqualTo(400);
        assertThat(data(request("POST","/mcp/servers",token,Map.of("provider","github"))).path("added").asBoolean()).isTrue();
        assertThat(data(request("PATCH","/mcp/servers/jobs",token,Map.of("enabled",false))).path("status").asText()).isEqualTo("DISABLED");
        assertThat(data(request("GET","/mcp/servers",token,null)).get(0).path("enabled").asBoolean()).isFalse();
        assertThat(data(request("GET","/mcp/servers",other,null)).get(0).path("enabled").asBoolean()).isTrue();
        assertThat(data(request("POST","/mcp/servers/jobs/refresh",token,null)).path("status").asText()).isEqualTo("DISABLED");
        assertThatThrownBy(()->connections.requireEnabled(owner,"jobs")).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        assertThat(request("POST","/mcp/servers/github/call",token,Map.of("tool","push_files","arguments",Map.of())).statusCode()).isEqualTo(502);
        jdbc.update("DELETE FROM users WHERE id=?",owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_mcp_servers WHERE user_id=?",Integer.class,owner)).isZero();
    }
    @Test void retrievalDebugRequiresCurrentDatabaseStaffRole() throws Exception {
        String token=guests.create().accessToken(), owner=guests.require("Bearer "+token);
        String ordinary=jwt.issue(owner,List.of("USER"),"fixture");
        String path="/staff/knowledge/retrieval-debug?query=Java";
        assertThat(request("GET",path,null,null).statusCode()).isEqualTo(401);
        assertThat(request("GET",path,ordinary,null).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE users SET roles=ARRAY['STAFF'] WHERE id=?",owner);
        String staff=jwt.issue(owner,List.of("STAFF"),"fixture");
        var result=data(request("GET",path,staff,null));
        assertThat(result.path("reranker").asText()).isEqualTo("query-term-coverage-v1");
        assertThat(result.path("vectorAvailable").asBoolean()).isFalse();
        jdbc.update("UPDATE users SET roles=ARRAY['USER'] WHERE id=?",owner);
        assertThat(request("GET",path,staff,null).statusCode()).isEqualTo(403);
    }
    private HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");
    }
}
