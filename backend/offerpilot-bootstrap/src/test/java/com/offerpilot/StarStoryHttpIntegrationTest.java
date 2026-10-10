package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.auth.application.PasswordLoginService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StarStoryHttpIntegrationTest extends SharedHttpIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String STRONG = "当时在电商库存项目中，我负责消除超卖。目标是保证下单一致性。"
            + "我通过引入分布式锁与补偿任务排查脏写，最终超卖降为 0，并用对账任务验证结果。";
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired PasswordLoginService logins;

    HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode data(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        return JSON.readTree(response.body()).path("data");
    }

    @Test
    void candidatesSaveDedupeAndOwnership() throws Exception {
        String owner = guests.create().accessToken();
        String other = logins.register("star-" + UUID.randomUUID() + "@example.test", "fixture-password").accessToken();
        String sessionId = data(call("POST", "/interviews", owner, Map.of("role", "Java 后端", "kind", "项目与经历")), 200).path("id").asText();
        data(call("PUT", "/interviews/" + sessionId + "/answers/1", owner, Map.of("answerText", STRONG)), 200);
        data(call("PUT", "/interviews/" + sessionId + "/answers/0", owner, Map.of("answerText", "很短")), 200);
        var candidates = data(call("GET", "/star-stories/candidates?sessionId=" + sessionId, owner, null), 200);
        assertThat(candidates.isArray()).isTrue();
        assertThat(candidates.size()).isEqualTo(1);
        assertThat(candidates.get(0).path("questionIndex").asInt()).isEqualTo(1);
        assertThat(candidates.get(0).path("alreadySaved").asBoolean()).isFalse();
        assertThat(call("POST", "/star-stories", owner, Map.of("sessionId", sessionId, "questionIndex", 0)).statusCode()).isEqualTo(400);
        var saved = data(call("POST", "/star-stories", owner, Map.of("sessionId", sessionId, "questionIndex", 1)), 200);
        assertThat(saved.path("starCount").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(data(call("POST", "/star-stories", owner, Map.of("sessionId", sessionId, "questionIndex", 1)), 200).path("id").asText())
                .isEqualTo(saved.path("id").asText());

        // The same body in another question still deduplicates after whitespace normalization.
        data(call("PUT", "/interviews/" + sessionId + "/answers/2", owner,
                Map.of("answerText", STRONG.replace(" 0", " \n 0"))), 200);
        assertThat(data(call("POST", "/star-stories", owner, Map.of("sessionId", sessionId, "questionIndex", 2)), 200).path("id"))
                .isEqualTo(saved.path("id"));
        assertThat(data(call("GET", "/star-stories", owner, null), 200).path("items").size()).isEqualTo(1);
        assertThat(call("GET", "/star-stories/candidates?sessionId=" + sessionId, other, null).statusCode()).isIn(404, 403);
        assertThat(call("POST", "/star-stories", other, Map.of("sessionId", sessionId, "questionIndex", 1)).statusCode()).isIn(404, 403);
        data(call("DELETE", "/star-stories/" + saved.path("id").asText(), owner, null), 200);
        assertThat(data(call("GET", "/star-stories", owner, null), 200).path("items").size()).isEqualTo(0);
    }

}
