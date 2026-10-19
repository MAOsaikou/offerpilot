package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.generation.*;
import com.offerpilot.interview.application.*;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.awaitility.Awaitility.await;

/** Full owned HTTP/task/validation/persistence lifecycle with a stub gateway, never a live provider. */
class InterviewRedesignHttpIntegrationTest extends SharedHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired GenerationTaskService tasks;
    @Autowired InterviewService interviews;
    @Autowired JdbcTemplate jdbc;
    @MockBean ModelGateway gateway;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void stub() {
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.3))).thenAnswer(call -> {
            List<Message> messages=call.getArgument(1); var input=json.readTree(messages.get(1).getText());
            var result=json.createObjectNode();
            if(input.has("answer")) {
                String answer=input.path("answer").asText();
                var dimensions=result.putObject("dimensions");
                for(String d:InterviewIntelligence.DIMENSIONS) dimensions.putObject(d).put("score",72).put("rationale","职责清楚，验证待补充").put("evidence",answer);
                result.put("rationale","说明了宣教行动，补充效果验证");
                result.putArray("strengths").add("行动具体"); result.putArray("weaknesses").add("缺少验证");
                result.putArray("improvedAnswerParts").addObject().put("section","行动").put("quote",answer).put("placeholder","【补充你的具体数据】");
            } else if(input.has("report")) {
                result.putArray("strengths").add("第1题职责明确"); result.putArray("weaknesses").add("第1题效果验证不足");result.putArray("advice").add("用真实结果重答宣教案例");
            } else {
                int count=input.path("options").path("questionCount").asInt();
                var questions=result.putArray("questions");
                for(int i=0;i<count;i++) questions.addObject().put("text","针对护理岗位的宣教场景，如何做出判断？第"+i+"种场景").put("type","CASE").put("difficulty","HARD");
            }
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(result.toString())))));
        });
    }
    HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path))
            .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception {assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");}
    String create(String token,int count) throws Exception {
        return data(request("POST","/interviews",token,Map.of("role","护士","kind","综合","questionCount",count,"difficulty","HARD","typeMix",Map.of("CASE",count),"industry","医疗"))).path("id").asText();
    }
    String task(String token,String kind,String id,Object input) throws Exception {
        String task=data(request("POST","/generation-tasks",token,Map.of("kind",kind,"entityId",id,"requestId",UUID.randomUUID().toString(),"input",input))).path("id").asText();
        return task;
    }
    JsonNode finish(String token,String task) throws Exception {
        String owner=guests.require("Bearer "+token);
        await().atMost(Duration.ofSeconds(15)).until(() -> !Set.of("QUEUED","RUNNING").contains(tasks.get(owner,task).status()));
        assertThat(tasks.get(owner,task).status()).withFailMessage("%s",tasks.get(owner,task).error()).isEqualTo("SUCCEEDED");
        return data(request("GET","/generation-tasks/"+task+"/result",token,null));
    }
    @Test void createsGeneratesScoresRestoresReportsAndLinksLearningWithOwnerIsolationAndLegacyCompatibility() throws Exception {
        String token=guests.create().accessToken(), other=guests.create().accessToken();String id=create(token,15);
        assertThat(interviews.get(guests.require("Bearer "+token),id).generationSource()).isEqualTo("pending");
        var generated=finish(token,task(token,"INTERVIEW_QUESTIONS",id,Map.of()));
        assertThat(generated.path("answers").size()).isEqualTo(15);
        assertThat(generated.path("answers").get(14).path("questionType").asText()).isEqualTo("CASE");
        assertThat(generated.path("generationSource").asText()).isEqualTo("model");
        data(request("PUT","/interviews/"+id+"/answers/14",token,Map.of("answerText","我负责护理宣教，采用图示解释注意事项。")));
        var scored=finish(token,task(token,"INTERVIEW_SCORE",id,Map.of("index",14)));
        assertThat(scored.path("answers").get(14).path("evaluation").path("score").asInt()).isEqualTo(72);
        assertThat(data(request("GET","/interviews/"+id,token,null)).path("answers").get(14).path("evaluation").path("improvedAnswer").asText()).contains("我负责护理宣教","【补充你的具体数据】");
        for(String kind:List.of("INTERVIEW_QUESTIONS","INTERVIEW_SCORE","INTERVIEW_REPORT"))
            assertThat(request("POST","/generation-tasks",other,Map.of("kind",kind,"entityId",id,"requestId",UUID.randomUUID().toString(),"input",Map.of("index",14))).statusCode()).isEqualTo(404);
        data(request("PATCH","/interviews/"+id,token,Map.of("completed",true)));
        var report=finish(token,task(token,"INTERVIEW_REPORT",id,Map.of()));
        assertThat(report.path("overall").asInt()).isEqualTo(72);
        assertThat(report.path("turns").get(0).path("score").isNull()).isTrue();
        assertThat(report.path("turns").get(14).path("score").asInt()).isEqualTo(72);
        assertThat(report.path("recommendedLearning").isEmpty()).isFalse();
        assertThat(data(request("GET","/interviews/"+id+"/report",token,null))).isEqualTo(report);
        data(request("POST","/career/learning-plans/from-interview/"+id,token,null));
        assertThat(request("GET","/interviews/"+id+"/report",other,null).statusCode()).isEqualTo(404);
        String old=data(request("POST","/interviews",token,Map.of("role","旧岗位","kind","项目与经历"))).path("id").asText();
        data(request("PATCH","/interviews/"+old,token,Map.of("completed",true)));
        var legacy=data(request("GET","/interviews/"+old+"/report",token,null));
        assertThat(legacy.path("note").asText()).contains("文字规则评分");assertThat(legacy.path("turns").size()).isEqualTo(5);
    }
    @Test void unavailableModelProducesLabeledRulesAndNullScoresAndAnswerEditsInvalidateFeedback() throws Exception {
        doThrow(new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.MODEL_UNAVAILABLE)).when(gateway).requireAvailable(any(),anyBoolean());
        String token=guests.create().accessToken();String id=create(token,3);
        var questions=finish(token,task(token,"INTERVIEW_QUESTIONS",id,Map.of()));
        assertThat(questions.path("generationSource").asText()).isEqualTo("rule");
        data(request("PUT","/interviews/"+id+"/answers/0",token,Map.of("answerText","我负责宣教")));
        var scored=finish(token,task(token,"INTERVIEW_SCORE",id,Map.of("index",0)));
        assertThat(scored.path("answers").get(0).path("evaluation").path("score").isNull()).isTrue();
        data(request("PATCH","/interviews/"+id,token,Map.of("completed",true)));
        assertThat(finish(token,task(token,"INTERVIEW_REPORT",id,Map.of())).path("overall").isNull()).isTrue();
        var edited=data(request("PUT","/interviews/"+id+"/answers/0",token,Map.of("answerText","我负责护理宣教并验证理解程度")));
        assertThat(edited.path("answers").get(0).path("evaluation").isNull()).isTrue();
        assertThat(data(request("GET","/interviews/"+id+"/report",token,null)).path("overall").isNull()).isTrue();
    }
    @Test void delayedScoreCannotOverwriteAnEditedAnswerAndCancelledWorkCannotPersist() throws Exception {
        String token=guests.create().accessToken(),owner=guests.require("Bearer "+token),id=create(token,3);
        finish(token,task(token,"INTERVIEW_QUESTIONS",id,Map.of()));
        data(request("PUT","/interviews/"+id+"/answers/0",token,Map.of("answerText","我负责护理宣教")));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var response=json.createObjectNode();response.putObject("dimensions"); // Invalid once, then fallback; must still reject stale snapshot.
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.3))).thenAnswer(call -> Flux.defer(() -> {
            entered.countDown();try {if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException();}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(response.toString())))));
        }));
        String task=task(token,"INTERVIEW_SCORE",id,Map.of("index",0));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        data(request("PUT","/interviews/"+id+"/answers/0",token,Map.of("answerText","已修改的真实回答")));release.countDown();
        await().atMost(Duration.ofSeconds(15)).until(() -> tasks.get(owner,task).status().equals("FAILED"));
        assertThat(interviews.get(owner,id).answers().get(0).evaluation()).isNull();
        var subscribed=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.3))).thenReturn(Flux.<ChatResponse>never()
                .doOnSubscribe(subscription -> subscribed.countDown()).doOnCancel(cancelled::countDown));
        String cancelTask=task(token,"INTERVIEW_SCORE",id,Map.of("index",0));
        assertThat(subscribed.await(10,TimeUnit.SECONDS)).isTrue();
        data(request("POST","/generation-tasks/"+cancelTask+"/cancel",token,null));
        assertThat(cancelled.await(10,TimeUnit.SECONDS)).isTrue();
        assertThat(tasks.get(owner,cancelTask).status()).isEqualTo("CANCELLED");
        assertThat(interviews.get(owner,id).answers().get(0).evaluation()).isNull();
    }
}
