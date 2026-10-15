package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.career.application.CareerRecommendForChat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CareerAssessmentHttpIntegrationTest extends SharedHttpIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired CurrentUser users;
    @Autowired CareerRecommendForChat careers;

    HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/career" + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }

    @Test void answerInferCorrectAndConfirmDrivesRecommendations() throws Exception {
        String token = guests.create().accessToken();
        var empty = data(call("GET", "/assessment", token, null));
        assertThat(empty.path("questions").size()).isEqualTo(7);
        assertThat(empty.path("inference").isNull()).isTrue();
        String[] answers = {"计算机专业大三", "我喜欢研究算法题，经常为了弄懂原理熬到很晚。",
                "我组织了社团的编程比赛，带领十个人完成报名和赛题。", "更喜欢钻研原理，也愿意动手做出能用的小工具。"};
        for (int i = 0; i < 3; i++) data(call("PUT", "/assessment/answers/" + i, token, Map.of("answer", answers[i])));
        // Fewer than four answers: not enough evidence for a profile.
        assertThat(call("POST", "/assessment/infer", token, null).statusCode()).isEqualTo(400);
        data(call("PUT", "/assessment/answers/3", token, Map.of("answer", answers[3])));
        assertThat(call("PUT", "/assessment/answers/7", token, Map.of("answer", "x")).statusCode()).isEqualTo(400);
        var inferred = data(call("POST", "/assessment/infer", token, null)).path("inference");
        // Offline test context: keyword rules, every quote taken from the answers.
        assertThat(inferred.path("source").asText()).isEqualTo("rule");
        assertThat(inferred.path("riasec").path("I").asInt()).isGreaterThan(inferred.path("riasec").path("A").asInt());
        assertThat(inferred.path("traits").findValuesAsText("name")).contains("好奇钻研", "主动推动");
        // Editing an answer makes the inference stale.
        assertThat(data(call("PUT", "/assessment/answers/0", token, Map.of("answer", "软件工程大三"))).path("inference").isNull()).isTrue();
        data(call("POST", "/assessment/infer", token, null));
        // Another user sees nothing of this assessment.
        assertThat(data(call("GET", "/assessment", guests.create().accessToken(), null)).path("answers").get(1).asText()).isEmpty();
        assertThat(call("PUT", "/assessment/confirm", token, Map.of("riasec", Map.of("R", 50, "I", 50, "A", 50, "S", 50, "E", 50, "C", 50),
                "traits", List.of())).statusCode()).isEqualTo(400);
        var confirmed = data(call("PUT", "/assessment/confirm", token, Map.of(
                "riasec", Map.of("R", 40, "I", 90, "A", 10, "S", 20, "E", 75, "C", 30),
                "traits", List.of("好奇钻研", "自己补充的特质"), "city", "杭州", "salary", "", "overtime", "")));
        assertThat(confirmed.path("confirmed").path("code").asText()).isEqualTo("IER");
        var traits = confirmed.path("confirmed").path("traits");
        assertThat(traits.get(0).path("evidence").asText()).contains("弄懂原理");
        assertThat(traits.get(1).path("evidence").asText()).isEmpty();
        assertThat(data(call("GET", "/profile", token, null)).path("city").asText()).isEqualTo("杭州");

        var result = careers.recommend(users.require("Bearer " + token));
        assertThat(result.payload().hollandCode()).isEqualTo("IER");
        assertThat(result.payload().recommendations()).isNotEmpty().allSatisfy(item ->
                assertThat(item.reasons()).anySatisfy(reason -> assertThat(reason).contains("你的原话").contains("弄懂原理")));

        assertThat(data(call("DELETE", "/assessment", token, null)).path("confirmed").isNull()).isTrue();
    }
}
