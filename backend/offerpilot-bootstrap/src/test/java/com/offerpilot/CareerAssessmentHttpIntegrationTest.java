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
    @Autowired com.offerpilot.memory.application.UserMemoryService memories;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    @Test void shortAnswersClarifyOncePerBaseAndNeverExceedTwelveWithOwnerIsolation() throws Exception {
        String token=guests.create().accessToken();
        for(int i=0;i<7;i++) {
            var saved=data(call("PUT","/assessment/answers/"+i,token,Map.of("answer","不知道")));
            assertThat(saved.path("questions").size()).isEqualTo(Math.min(8+i,12));
            assertThat(saved.path("questions")).allSatisfy(q -> assertThat(q.asText()).isNotBlank());
        }
        var repeated=data(call("PUT","/assessment/answers/0",token,Map.of("answer","还没想好")));
        assertThat(repeated.path("questions").size()).isEqualTo(12);
        for(int i=7;i<12;i++) data(call("PUT","/assessment/answers/"+i,token,Map.of("answer","我喜欢编程，做过一个可用的小项目")));
        assertThat(data(call("GET","/assessment",token,null)).path("answers").get(7).asText()).contains("可用的小项目");
        assertThat(data(call("GET","/assessment",guests.create().accessToken(),null)).path("questions").size()).isEqualTo(7);
        assertThat(call("PUT","/assessment/answers/12",token,Map.of("answer","越界")).statusCode()).isEqualTo(400);
    }
    @Test void questionnaireResumesAndExplicitCompletionReplacesOnlyInterests() throws Exception {
        String token=guests.create().accessToken();
        var confirmed=data(call("PUT","/assessment/confirm",token,Map.of("riasec",Map.of("R",20,"I",90,"A",10,"S",40,"E",60,"C",30),
                "traits",List.of("细致"),"city","杭州","salary","12k","overtime","双休"))).path("confirmed");
        data(call("PUT","/questionnaire/answers/R1",token,Map.of("value",1)));
        assertThat(data(call("GET","/questionnaire",token,null)).path("answers").path("R1").asInt()).isEqualTo(1);
        assertThat(data(call("GET","/questionnaire",guests.create().accessToken(),null)).path("answers").size()).isZero();
        assertThat(call("PUT","/questionnaire/answers/R1",token,Map.of("value",6)).statusCode()).isEqualTo(400);
        assertThat(call("POST","/questionnaire/complete",token,Map.of("revision",1)).statusCode()).isEqualTo(400);
        JsonNode state=null;
        for(var q:com.offerpilot.career.application.CareerQuestionnaire.QUESTIONS)
            state=data(call("PUT","/questionnaire/answers/"+q.id(),token,Map.of("value",q.axis().equals("I")?5:q.axis().equals("A")?4:q.axis().equals("S")?3:1)));
        assertThat(data(call("GET","/profile",token,null)).path("assessment")).isEqualTo(confirmed);
        assertThat(call("POST","/questionnaire/complete",token,Map.of("revision",0)).statusCode()).isEqualTo(400);
        var done=data(call("POST","/questionnaire/complete",token,Map.of("revision",state.path("revision").asInt())));
        assertThat(done.path("code").asText()).isEqualTo("IAS");
        var merged=data(call("GET","/profile",token,null)).path("assessment");
        assertThat(merged.path("code").asText()).isEqualTo("IAS");
        assertThat(merged.path("traits")).isEqualTo(confirmed.path("traits"));
        assertThat(merged.path("constraints")).isEqualTo(confirmed.path("constraints"));
        data(call("POST","/questionnaire/complete",token,Map.of("revision",state.path("revision").asInt())));
        var result=careers.recommend(users.require("Bearer "+token));
        assertThat(result.payload().recommendations()).hasSizeBetween(5,8).allSatisfy(card -> {
            assertThat(card.details().sourceOccupationId()).isEqualTo(card.occupationId());
            assertThat(card.details().evidence()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(card.details().typicalTitles()).isNotEmpty();assertThat(card.details().companies()).isNotEmpty();
            assertThat(card.details().salaryRange()).isNotBlank();assertThat(card.details().concerns()).isNotBlank();
        });
        data(call("PUT","/questionnaire/answers/R1",token,Map.of("value",5)));
        assertThat(data(call("GET","/questionnaire",token,null)).path("completed").asBoolean()).isFalse();
    }

    @Test void threePanelsRoundTripAndLegacyWritesPreserveNewFields() throws Exception {
        String token = guests.create().accessToken();
        var empty = data(call("GET", "/profile", token, null));
        assertThat(empty.path("major").asText()).isEmpty();
        assertThat(empty.path("assessment").isNull()).isTrue();
        var fields = Map.of("target", "工程师", "city", "成都", "skills", "SQL", "interests", List.of("技术研发"),
                "major", "软件工程", "courses", "数据库\n算法", "valuesText", "成长\n平衡");
        var saved = data(call("PUT", "/profile", token, fields));
        assertThat(saved.path("courses").asText()).isEqualTo("数据库\n算法");
        var legacy = data(call("PUT", "/profile", token, Map.of("target", "新目标", "city", "成都", "skills", "SQL", "interests", List.of())));
        assertThat(legacy.path("major").asText()).isEqualTo("软件工程");
        assertThat(legacy.path("valuesText").asText()).isEqualTo("成长\n平衡");
        assertThat(call("PUT", "/profile", token, Map.of("target", "", "city", "", "skills", "", "interests", List.of(), "major", 42)).statusCode()).isEqualTo(400);
        assertThat(data(call("GET", "/profile", guests.create().accessToken(), null)).path("major").asText()).isEmpty();
    }

    @Test void memorySyncIsIdempotentImportsEmptyFieldsAndHonorsToggleAndClear() throws Exception {
        String token = guests.create().accessToken();
        String owner = users.require("Bearer " + token);
        var kind = com.offerpilot.memory.application.UserMemoryService.Kind.OTHER;
        memories.add(owner, new com.offerpilot.memory.application.UserMemoryService.Draft(kind, "专业：统计学"));
        memories.add(owner, new com.offerpilot.memory.application.UserMemoryService.Draft(kind, "意向城市：成都"));
        var imported = data(call("GET", "/profile", token, null));
        assertThat(imported.path("major").asText()).isEqualTo("统计学");
        assertThat(imported.path("city").asText()).isEqualTo("成都");
        assertThat(data(call("GET", "/profile", token, null)).path("updatedAt")).isEqualTo(imported.path("updatedAt"));
        var fields = Map.of("target", "分析师", "city", "成都", "skills", "SQL", "interests", List.of(), "major", "计算机");
        data(call("PUT", "/profile", token, fields));
        var first = memories.list(owner).items();
        data(call("PUT", "/profile", token, fields));
        assertThat(memories.list(owner).items()).isEqualTo(first);
        data(call("PUT", "/profile", token, Map.of("target", "工程师", "city", "成都", "skills", "SQL", "interests", List.of())));
        assertThat(memories.list(owner).items()).hasSize(3);
        var targetFact = memories.list(owner).items().stream().filter(m -> m.fact().startsWith("目标职业：")).findFirst().orElseThrow();
        assertThat(targetFact.fact()).isEqualTo("目标职业：工程师");
        assertThat(first).anySatisfy(f -> assertThat(f.id()).isEqualTo(targetFact.id()));
        // Memory management must not overwrite a populated user edit.
        memories.upsertPrefixed(owner, kind, "专业：", "物理学");
        assertThat(data(call("GET", "/profile", token, null)).path("major").asText()).isEqualTo("计算机");
        memories.settings(owner, false);
        data(call("PUT", "/profile", token, Map.of("target", "保留自填目标", "city", "成都", "skills", "", "interests", List.of(), "major", "")));
        assertThat(data(call("GET", "/profile", token, null)).path("major").asText()).isEmpty();
        assertThat(memories.list(owner).items()).anySatisfy(m -> assertThat(m.fact()).isEqualTo("目标职业：工程师"));
        memories.settings(owner, true);
        data(call("PUT", "/profile", token, Map.of("target", "", "city", "", "skills", "", "interests", List.of(), "major", "")));
        assertThat(memories.list(owner).items()).isEmpty();
        assertThat(data(call("GET", "/profile", token, null)).path("major").asText()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT major FROM career_profiles WHERE user_id=?", String.class, owner)).isEmpty();
    }

    @Test void memoryCapRollsBackProfileWriteAndExistingPrefixCanStillUpdate() throws Exception {
        String token = guests.create().accessToken();
        String owner = users.require("Bearer " + token);
        memories.upsertPrefixed(owner, com.offerpilot.memory.application.UserMemoryService.Kind.GOAL, "目标职业：", "旧目标");
        for (int i = 1; i < 50; i++) memories.add(owner, new com.offerpilot.memory.application.UserMemoryService.Draft(
                com.offerpilot.memory.application.UserMemoryService.Kind.OTHER, "事实" + i));
        data(call("PUT", "/profile", token, Map.of("target", "新目标", "city", "", "skills", "", "interests", List.of())));
        var before = data(call("GET", "/profile", token, null));
        assertThat(call("PUT", "/profile", token, Map.of("target", "会回滚", "city", "杭州", "skills", "", "interests", List.of())).statusCode()).isEqualTo(409);
        assertThat(data(call("GET", "/profile", token, null))).isEqualTo(before);
        assertThat(memories.list(owner).items()).hasSize(50).anySatisfy(m -> assertThat(m.fact()).isEqualTo("目标职业：新目标"));
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
        var profile = data(call("GET", "/profile", token, null));
        assertThat(profile.path("city").asText()).isEqualTo("杭州");
        assertThat(profile.path("assessment")).isEqualTo(confirmed.path("confirmed"));
        assertThat(memories.list(users.require("Bearer " + token)).items()).anySatisfy(m -> assertThat(m.fact()).isEqualTo("兴趣码：IER"));
        data(call("PUT", "/assessment/answers/0", token, Map.of("answer", "修订后的专业")));
        var edited = data(call("PUT", "/assessment/confirm", token, Map.of(
                "riasec", Map.of("R", 40, "I", 90, "A", 10, "S", 20, "E", 75, "C", 30),
                "traits", List.of("好奇钻研", "自己补充的特质"), "city", "杭州")));
        assertThat(edited.path("confirmed").path("traits").get(0).path("evidence").asText()).contains("弄懂原理");

        var result = careers.recommend(users.require("Bearer " + token));
        assertThat(result.payload().hollandCode()).isEqualTo("IER");
        assertThat(result.payload().recommendations()).isNotEmpty().allSatisfy(item ->
                assertThat(item.reasons()).anySatisfy(reason -> assertThat(reason).contains("你的原话").contains("弄懂原理")));

        assertThat(data(call("DELETE", "/assessment", token, null)).path("confirmed").isNull()).isTrue();
        assertThat(memories.list(users.require("Bearer " + token)).items()).noneSatisfy(m -> assertThat(m.fact()).startsWith("兴趣码："));
    }
}
