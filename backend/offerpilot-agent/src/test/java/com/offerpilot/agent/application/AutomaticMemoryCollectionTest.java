package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.memory.application.AutomaticMemoryExtractor;
import com.offerpilot.memory.application.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomaticMemoryCollectionTest {
    final UserMemoryService memories=mock(UserMemoryService.class);
    final AutomaticMemoryExtractor extractor=mock(AutomaticMemoryExtractor.class);
    final MemoryChat chat=new MemoryChat(memories,mock(ApprovalService.class),new ObjectMapper());
    AutomaticMemoryCollectionTest() { ReflectionTestUtils.setField(chat,"automatic",extractor); }
    @Test void collectsLocationCompanyPreferenceAndCityExclusionThroughRealOfflineRules() {
        var offline = new AutomaticMemoryExtractor(
                new StaticListableBeanFactory().getBeanProvider(ModelGateway.class), new ObjectMapper());
        ReflectionTestUtils.setField(chat,"automatic",offline);
        when(memories.enabled("alice")).thenReturn(true);
        var texts = List.of("我想在杭州做后端开发，偏好中型互联网公司",
                "我以后想在杭州工作，不考虑北京，比较偏好中型互联网公司。");
        var expected = List.of(
                List.of(rule(UserMemoryService.Kind.LOCATION,"我想在杭州做后端开发"),
                        rule(UserMemoryService.Kind.PREFERENCE,"偏好中型互联网公司")),
                List.of(rule(UserMemoryService.Kind.LOCATION,"我以后想在杭州工作"),
                        rule(UserMemoryService.Kind.CONSTRAINT,"不考虑北京"),
                        rule(UserMemoryService.Kind.PREFERENCE,"比较偏好中型互联网公司")));
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            var facts = AutomaticMemoryExtractor.rules(text);
            assertThat(facts).containsExactlyElementsOf(expected.get(i));
            assertThat(facts).allSatisfy(f -> assertThat(text).contains(f.fact()));
            var source = new UserMemoryService.MessageSource("conversation","message-"+i,text);
            when(memories.extractionSource("alice","run-"+i)).thenReturn(source);
            chat.collect("alice","run-"+i).blockLast(Duration.ofSeconds(2));
            verify(memories).suggest("alice","run-"+i,source,expected.get(i));
        }
    }
    @Test void rulesRejectNewPhrasingsInsideQuestionsHypothesesThirdPartyAndDocumentPastes() {
        for (String text : List.of("我以后想在杭州工作，不考虑北京，比较偏好中型互联网公司？",
                "我想在杭州做后端开发，偏好中型互联网公司，可以吗？", "不考虑北京？",
                "如果我偏好中型互联网公司", "假如我以后想在杭州工作，偏好中型互联网公司",
                "可能我以后想在杭州工作，不考虑北京", "我以后想在杭州工作，不确定是否偏好中型互联网公司",
                "朋友说偏好中型互联网公司", "朋友说，偏好中型互联网公司，不考虑北京",
                "以下是JD：\n我想在杭州做后端开发，偏好中型互联网公司",
                "jd，偏好中型互联网公司", "工具输出，不考虑北京，比较偏好中型互联网公司",
                "ASSISTANT\n我以后想在杭州工作，偏好中型互联网公司", "系统提示，偏好中型互联网公司",
                "简历\n我以后想在杭州工作，不考虑北京，比较偏好中型互联网公司。"))
            assertThat(AutomaticMemoryExtractor.rules(text)).as(text).isEmpty();
    }
    @Test void modelCompanyClaimsStillRequireExactRuleApprovedSpans() {
        var gateway = mock(ModelGateway.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("gateway",gateway);
        var grounded = new AutomaticMemoryExtractor(beans.getBeanProvider(ModelGateway.class),new ObjectMapper());
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(Flux.just(new ChatResponse(List.of(
                new Generation(new AssistantMessage("""
                        [{"fact":"我以后想在杭州工作","confidence":0.9},
                         {"fact":"比较偏好中型互联网公司","confidence":0.9},
                         {"fact":"中型互联网公司","confidence":0.9},
                         {"fact":"我偏好中型互联网公司","confidence":0.9}]
                        """))))));
        var facts = grounded.extract("alice","我以后想在杭州工作，不考虑北京，比较偏好中型互联网公司。");
        assertThat(facts).extracting(AutomaticMemoryExtractor.Fact::fact)
                .containsExactly("我以后想在杭州工作","比较偏好中型互联网公司");
        assertThat(facts).extracting(AutomaticMemoryExtractor.Fact::kind)
                .containsExactly(UserMemoryService.Kind.LOCATION,UserMemoryService.Kind.PREFERENCE);
        assertThat(facts).allSatisfy(f -> assertThat(f.extractor()).isEqualTo("model"));
    }
    private static AutomaticMemoryExtractor.Fact rule(UserMemoryService.Kind kind, String fact) {
        return new AutomaticMemoryExtractor.Fact(kind,fact,.75,"rule");
    }
    @Test void collectsPersistedUserMessageOnlyAndOrdinaryFactsDoNotBlockOnChatApproval() {
        when(memories.enabled("alice")).thenReturn(true);
        var source=new UserMemoryService.MessageSource("conversation","user-message","我喜欢研究算法");
        var facts=List.of(new AutomaticMemoryExtractor.Fact(UserMemoryService.Kind.PREFERENCE,source.text(),.75,"rule"));
        when(memories.extractionSource("alice","run")).thenReturn(source);
        var scope=new com.offerpilot.llm.UsageJournal.Scope("alice","run","reply-message");
        when(extractor.extract("alice",source.text())).thenAnswer(c -> {
            assertThat(com.offerpilot.llm.UsageJournal.current()).isEqualTo(scope);return facts;
        });
        chat.collect("alice","run").contextWrite(c -> c.put(com.offerpilot.llm.UsageJournal.KEY,scope)).blockLast(Duration.ofSeconds(2));
        assertThat(com.offerpilot.llm.UsageJournal.current()).isNull();
        verify(memories).suggest("alice","run",source,facts);
        assertThat(chat.candidate("alice","我的目标城市是杭州")).isNull();
        assertThat(chat.candidate("alice","记住：我的目标城市是杭州")).isNotNull();
    }
    @Test void cancelDuringExtractionSuppressesLateCandidateWrite() {
        when(memories.enabled("alice")).thenReturn(true);
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        var source=new UserMemoryService.MessageSource("c","m","我喜欢研究算法");
        when(memories.extractionSource("alice","run")).thenReturn(source);
        when(extractor.extract("alice",source.text())).thenAnswer(c -> { active.set(false); return List.of(); });
        chat.collect("alice","run",active::get).blockLast(Duration.ofSeconds(2));
        verify(memories,never()).suggest(anyString(),anyString(),any(),anyList());
    }
    @Test void memoryOffSkipsBothExtractionAndCandidateWriteAndFailureDoesNotFailChat() {
        when(memories.enabled("alice")).thenReturn(false);
        chat.collect("alice","run").blockLast(Duration.ofSeconds(2));
        verifyNoInteractions(extractor);
        verify(memories,never()).extractionSource(anyString(),anyString());
        when(memories.enabled("alice")).thenReturn(true);
        when(memories.extractionSource("alice","run")).thenThrow(new IllegalStateException("offline"));
        assertThatCode(() -> chat.collect("alice","run").blockLast(Duration.ofSeconds(2))).doesNotThrowAnyException();
    }
}
