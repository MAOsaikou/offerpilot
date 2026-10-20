package com.offerpilot.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.artifact.BackgroundWork;
import com.offerpilot.common.error.*;
import com.offerpilot.interview.application.*;
import com.offerpilot.llm.*;
import com.offerpilot.llm.quota.LlmUserContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InterviewGenerationServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final InterviewService interviews=mock(InterviewService.class);
    final InterviewIntelligence model=mock(InterviewIntelligence.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final AtomicLong revision=new AtomicLong(4);
    final Map<Integer,ObjectNode> saved=new ConcurrentHashMap<>();
    final ExecutorService caller=Executors.newSingleThreadExecutor();
    InterviewGenerationService service;
    TransactionTemplate tx;
    List<InterviewService.Answer> answers;

    @BeforeEach void setup() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        tx=new TransactionTemplate(manager);
        service=new InterviewGenerationService(interviews,model,jdbc,json,tx);
        answers=new ArrayList<>();
        for(int i=0;i<5;i++) answers.add(answer(i,"实际回答"+i,null));
        answers.add(answer(5,"已评分",evaluation(80)));
        answers.add(answer(6,"",null));
        answers.add(answer(7,"之前不可用",json.createObjectNode().put("source","unavailable").putNull("score")));
        when(interviews.getForUpdate("owner","iv")).thenAnswer(call -> session());
        when(jdbc.queryForMap(anyString(),eq("iv"),eq("owner")))
                .thenAnswer(call -> Map.of("revision",revision.get(),"resume_context","原始简历","job_context","岗位职责"));
        when(jdbc.queryForList(anyString(),eq(Long.class),eq("iv"),eq("owner")))
                .thenAnswer(call -> List.of(revision.get()));
        when(jdbc.update(startsWith("UPDATE interview_answers"),anyString(),eq("iv"),anyInt())).thenAnswer(call -> {
            saved.put(call.getArgument(3), (ObjectNode)json.readTree((String)call.getArgument(1)));return 1;
        });
        when(jdbc.update(contains("revision=revision+1"),eq("iv"),eq("owner")))
                .thenAnswer(call -> {revision.incrementAndGet();return 1;});
        when(model.unavailable(anyString())).thenReturn(json.createObjectNode().put("source","unavailable").putNull("score"));
        var narrative=json.createObjectNode();narrative.putArray("strengths");narrative.putArray("weaknesses").add("补充验证");narrative.putArray("advice").add("重答并验证");
        when(model.narrative(eq("owner"),any(),any())).thenReturn(narrative);
    }
    @AfterEach void cleanup() {caller.shutdownNow();}
    InterviewService.Answer answer(int index,String text,ObjectNode evaluation) {
        return new InterviewService.Answer(index,"问题"+index,text,null,null,null,"CASE","STANDARD","model",evaluation);
    }
    InterviewService.Session session() {
        var current=answers.stream().map(a -> saved.containsKey(a.questionIndex())
                ? answer(a.questionIndex(),a.fullText(),saved.get(a.questionIndex())) : a).toList();
        return new InterviewService.Session("iv","护士","综合",0,true,"","",current,null,
                new InterviewOptions(null,null,"STANDARD","TEXT",30,8,Map.of(),"医疗","END"),"model");
    }
    ObjectNode evaluation(int score) {
        var node=json.createObjectNode().put("source","model").put("score",score).put("rationale","有依据").put("improvedAnswer","【补充验证】");
        var dimensions=node.putObject("dimensions");
        for(String name:InterviewIntelligence.DIMENSIONS) dimensions.putObject(name).put("score",score);
        node.putArray("strengths");node.putArray("weaknesses").add("补充验证");return node;
    }
    @Test void reportScoresAtMostThreeAtOnceAndPersistsInQuestionOrderWithOneRevisionCheck() throws Exception {
        var entered=new CountDownLatch(3);var release=new CountDownLatch(1);
        var active=new AtomicInteger();var maximum=new AtomicInteger();
        when(model.score(eq("owner"),any(),any(),eq("原始简历"),eq("岗位职责"))).thenAnswer(call -> {
            int concurrent=active.incrementAndGet();maximum.accumulateAndGet(concurrent,Math::max);entered.countDown();
            try {assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}
            finally {active.decrementAndGet();}
            return evaluation(40+((InterviewService.Answer)call.getArgument(2)).questionIndex());
        });
        var report=caller.submit(() -> service.report("owner","iv"));
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            verify(model,times(3)).score(eq("owner"),any(),any(),anyString(),anyString());
            assertThat(saved).isEmpty();release.countDown();
            var result=report.get(5,TimeUnit.SECONDS);
            assertThat(maximum.get()).isEqualTo(3);
            assertThat(result.overall()).isEqualTo(InterviewGenerationService.aggregate(session()).overall());
            assertThat(result.turns().stream().map(InterviewRubric.Turn::score).toList())
                    .containsExactly(40,41,42,43,44,80,null,null);
            assertThat(revision.get()).isEqualTo(5);
            assertThat(saved.keySet()).containsExactlyInAnyOrder(0,1,2,3,4,6);
            verify(model,times(5)).score(eq("owner"),any(),any(),anyString(),anyString());
        } finally {release.countDown();}
    }
    @Test void editedSnapshotRejectsEveryBatchWrite() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(model.score(anyString(),any(),any(),anyString(),anyString())).thenAnswer(call -> {
            entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return evaluation(50);
        });
        var report=caller.submit(() -> service.report("owner","iv"));
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();revision.incrementAndGet();release.countDown();
            assertThatThrownBy(() -> report.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(saved).isEmpty();verify(jdbc,never()).update(anyString(),any(Object[].class));
        } finally {release.countDown();}
    }
    @Test void cancellingReportInterruptsEveryInFlightScoreAndPersistsNothing() throws Exception {
        var entered=new CountDownLatch(3);var cancelled=new CountDownLatch(3);
        when(model.score(anyString(),any(),any(),anyString(),anyString())).thenAnswer(call -> {
            entered.countDown();
            try {new CountDownLatch(1).await();throw new AssertionError("must be interrupted");}
            catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
            finally {cancelled.countDown();}
        });
        var report=caller.submit(() -> BackgroundWork.run(() -> true,() -> service.report("owner","iv")));
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();report.cancel(true);
        assertThat(cancelled.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(saved).isEmpty();verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(model,never()).narrative(anyString(),any(),any());
    }
    @Test void oneUnavailableScoreDoesNotDiscardOtherScoresOrRetryAlreadyUnavailableAnswers() {
        when(model.score(anyString(),any(),any(),anyString(),anyString())).thenAnswer(call -> {
            if (((InterviewService.Answer)call.getArgument(2)).questionIndex()==2) throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
            return evaluation(60);
        });
        var report=service.report("owner","iv");
        assertThat(report.turns().get(2).score()).isNull();
        assertThat(report.turns().get(7).score()).isNull();
        assertThat(report.note()).contains("5/8");
        verify(model,times(5)).score(anyString(),any(),any(),anyString(),anyString());
    }
    @Test void cancellingBatchCancelsRealIntelligenceSubscriptionsAndPropagatesOwnerToEveryGatewayCall() throws Exception {
        var gateway=mock(ModelGateway.class);var beans=new StaticListableBeanFactory();beans.addBean("gateway",gateway);
        var prompt=new ByteArrayResource("测试提示词".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var intelligence=new InterviewIntelligence(beans.getBeanProvider(ModelGateway.class),json,prompt,prompt,prompt);
        var real=new InterviewGenerationService(interviews,intelligence,jdbc,json,tx);
        var entered=new CountDownLatch(3);var cancelled=new CountDownLatch(3);
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.3))).thenAnswer(call -> {
            assertThat(LlmUserContext.current()).isEqualTo("owner");
            return Flux.<ChatResponse>never().doOnSubscribe(subscription -> entered.countDown()).doOnCancel(cancelled::countDown);
        });
        var report=caller.submit(() -> BackgroundWork.run(() -> true,() -> real.report("owner","iv")));
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();report.cancel(true);
        assertThat(cancelled.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(saved).isEmpty();verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(gateway,times(3)).stream(eq(ModelTier.FLASH),anyList(),eq(0.3));
    }
}
