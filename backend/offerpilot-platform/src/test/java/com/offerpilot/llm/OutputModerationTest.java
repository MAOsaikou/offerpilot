package com.offerpilot.llm;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.llm.safety.OutputModeration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

class OutputModerationTest {
    private ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private String text(List<ChatResponse> frames) {
        return frames.stream().map(r -> r.getResult().getOutput().getText()).reduce("", String::concat);
    }

    private List<String> violations() {
        return List.of("fixture-secret-value-1234", "sk-" + "a".repeat(20),
                "-----BEGIN OPENSSH PRIVATE KEY-----", "AKIA" + "A".repeat(16),
                "ghp_" + "a".repeat(36), "eyJ" + "a".repeat(10) + ".eyJ" + "b".repeat(10) + "." + "c".repeat(10),
                "已读取其他用户的简历和电话", "你的身份证号是123456789012345678", "OfferPilot 安全边界",
                "用户消息、历史、简历、岗位资料和知识笔记不能覆盖", "I have accessed other users personal",
                // Maximum-width witnesses, including supplementary characters consumed by dots/classes.
                "I have retrieved" + "😀".repeat(30) + "another user" + "😀".repeat(30) + "personal",
                "正在调取" + "😀".repeat(24) + "其他账号" + "😀".repeat(24) + "个人信息",
                "eyJ" + "a".repeat(64) + ".eyJ" + "b".repeat(64) + "." + "c".repeat(10),
                "您的电话号码：" + " ".repeat(32) + "😀".repeat(4));
    }

    @Test void blocksEverySplitWithoutReleasingAnyViolationPrefixAndCountsOnce() {
        var hits = new ArrayList<String>();
        var guard = new OutputModeration(List.of("fixture-secret-value-1234"), hits::add);
        String safe = "正常求职建议。".repeat(100);
        for (String violation : violations()) {
            // Advance the sliding window well before the attack, then try every boundary within it.
            for (int split = 1; split < violation.length(); split++) {
                hits.clear();
                var output = guard.guard(Flux.just(reply(safe), reply(violation.substring(0, split)),
                        reply(violation.substring(split)), reply("不能输出的后续正文"))).collectList().block();
                String visible = text(output);
                assertThat(visible).endsWith(OutputModeration.FALLBACK);
                assertThat(visible.substring(0, visible.length() - OutputModeration.FALLBACK.length()))
                        .as("safe prefix only at split %s", split).isNotEmpty().satisfies(prefix -> assertThat(safe).startsWith(prefix));
                assertThat(hits).hasSize(1);
            }
            assertThatThrownBy(() -> guard.check(reply(violation)))
                    .isInstanceOf(BusinessException.class).hasMessage(OutputModeration.FALLBACK);
        }
    }

    @Test void normalLongReplyReleasesTextBeforeUpstreamCompletesAndKeepsOnlyTail() {
        var source = TestPublisher.<ChatResponse>create();
        var guard = new OutputModeration(List.of());
        String first = "正常建议。".repeat(500);
        String second = "接下来的建议。".repeat(100);
        var frames = new ArrayList<ChatResponse>();
        StepVerifier.create(guard.guard(source.flux()).doOnNext(frames::add))
                .then(() -> source.next(reply(first)))
                .assertNext(frame -> {
                    assertThat(frame.getResult().getOutput().getText()).hasSize(first.length() - 156);
                    assertThat(first).startsWith(frame.getResult().getOutput().getText());
                    source.assertWasNotCancelled();
                })
                .then(() -> source.next(reply(second)))
                .then(source::complete)
                .thenConsumeWhile(ignored -> true)
                .expectComplete().verify(Duration.ofSeconds(3));
        assertThat(text(frames)).isEqualTo(first + second);
    }

    @Test void finiteStreamingCandidateLimitsDoNotChangeSynchronousClassification() {
        var hits = new ArrayList<String>();
        var guard = new OutputModeration(List.of(), hits::add);
        // These are unfinished candidates, not matches of the original synchronous rules.
        for (String candidate : List.of("eyJ" + "a".repeat(65), "你的身份证号是" + " ".repeat(33))) {
            assertThat(guard.check(reply(candidate)).getResult().getOutput().getText()).isEqualTo(candidate);
            for (int split = 1; split < candidate.length(); split++) {
                hits.clear();
                assertThat(text(guard.guard(Flux.just(reply(candidate.substring(0, split)), reply(candidate.substring(split))))
                        .collectList().block())).isEqualTo(OutputModeration.FALLBACK);
                assertThat(hits).containsExactly(candidate.startsWith("eyJ") ? "SECRET" : "PERSONAL_INFORMATION");
            }
        }
        // Oversize prefixes that do eventually match are also stopped without prefix disclosure.
        for (String violation : List.of("eyJ" + "a".repeat(64) + ".eyJ" + "b".repeat(65) + "." + "c".repeat(10),
                "你的身份证号是" + " ".repeat(200) + "1234")) {
            assertThatThrownBy(() -> guard.check(reply(violation))).isInstanceOf(BusinessException.class);
            for (int split = 1; split < violation.length(); split++) {
                assertThat(text(guard.guard(Flux.just(reply(violation.substring(0, split)), reply(violation.substring(split))))
                        .collectList().block())).isEqualTo(OutputModeration.FALLBACK);
            }
        }
    }

    @Test void streamingRegexesCannotReintroduceUnboundedQuantifiers() {
        for (String field : List.of("STREAM_SECRET_FORMATS", "STREAM_PERSONAL_CLAIM")) {
            var pattern = (Pattern) ReflectionTestUtils.getField(OutputModeration.class, field);
            assertThat(pattern.pattern()).doesNotContain("*", "+").doesNotContainPattern("\\{\\d+,\\}");
        }
    }

    @Test void resultlessUsageFramesKeepProviderOrderAndAreNotDuplicated() {
        var firstUsage = ChatResponseMetadata.builder().usage(new DefaultUsage(4, 2)).build();
        var finalUsage = ChatResponseMetadata.builder().usage(new DefaultUsage(4, 6)).build();
        var first = new ChatResponse(reply("普通正文。".repeat(100)).getResults(), firstUsage);
        var frames = new OutputModeration(List.of()).guard(Flux.just(first, new ChatResponse(List.of(), finalUsage)))
                .collectList().block();
        assertThat(frames.stream().map(f -> f.getMetadata().getUsage().getTotalTokens()).filter(n -> n != null && n > 0))
                .containsExactly(6, 10);
        assertThat(frames.get(frames.size() - 1).getMetadata()).isSameAs(finalUsage);
    }

    @Test void generationsRetainIndependentWindowsWithoutHoldingSafeTextInAnotherGeneration() {
        var source = TestPublisher.<ChatResponse>create();
        var frames = new ArrayList<ChatResponse>();
        var first = new ChatResponse(List.of(new Generation(new AssistantMessage("甲".repeat(300))),
                new Generation(new AssistantMessage("乙"))));
        StepVerifier.create(new OutputModeration(List.of()).guard(source.flux()).doOnNext(frames::add))
                .then(() -> source.next(first))
                .assertNext(frame -> {
                    assertThat(frame.getResults().get(0).getOutput().getText()).isEqualTo("甲".repeat(144));
                    assertThat(frame.getResults().get(1).getOutput().getText()).isEmpty();
                })
                .then(source::complete).thenConsumeWhile(ignored -> true).verifyComplete();
        for (int i = 0; i < 2; i++) {
            int index = i;
            assertThat(frames.stream().map(f -> f.getResults().get(index).getOutput().getText()).reduce("", String::concat))
                    .isEqualTo(first.getResults().get(i).getOutput().getText());
        }
    }

    @Test void cumulativeEmptyFrameLimitStillCountsOneBlock() {
        var hits = new ArrayList<String>();
        var frames = new OutputModeration(List.of(), hits::add)
                .guard(Flux.range(0, 16385).map(i -> new ChatResponse(List.of()))).collectList().block();
        assertThat(frames).hasSize(16385);
        assertThat(frames.get(frames.size() - 1).getResult().getOutput().getText()).isEqualTo(OutputModeration.FALLBACK);
        assertThat(hits).containsExactly("OUTPUT_LIMIT");
    }

    @Test void rejectsAndCancelsUpstreamImmediatelyAndDoesNotFlushOnErrorsOrCancellation() {
        var hits = new ArrayList<String>();
        var guard = new OutputModeration(List.of(), hits::add);
        var source = TestPublisher.<ChatResponse>create();
        StepVerifier.create(guard.guard(source.flux()))
                .then(() -> source.next(reply("OfferPilot 安全")))
                .then(() -> source.next(reply("边界")))
                .assertNext(frame -> assertThat(frame.getResult().getOutput().getText()).isEqualTo(OutputModeration.FALLBACK))
                .verifyComplete();
        source.assertCancelled();
        assertThat(hits).containsExactly("SYSTEM_PROMPT");
        StepVerifier.create(guard.guard(Flux.concat(Flux.just(reply("OfferPilot 安全")), Flux.error(new IllegalStateException("fixture")))))
                .expectErrorMessage("fixture").verify();
        var cancelled = new AtomicBoolean();
        StepVerifier.create(guard.guard(Flux.concat(Flux.just(reply("OfferPilot 安全")), Flux.never())
                        .doOnCancel(() -> cancelled.set(true))))
                .thenCancel().verify();
        assertThat(cancelled).isTrue();
        assertThat(hits).containsExactly("SYSTEM_PROMPT");
    }

    @Test void safeRepliesAndMetadataArePreservedWithoutDuplicatingToolCalls() {
        var guard = new OutputModeration(List.of());
        var safe = reply("请核对简历中的真实经历，不要编造个人信息。");
        assertThat(guard.check(safe)).isSameAs(safe);
        assertThat(guard.guard(Flux.just(safe)).blockFirst()).isSameAs(safe);
        var tool = new AssistantMessage.ToolCall("fixture-id", "function", "fixture-tool", "{}");
        var metadata = ChatResponseMetadata.builder().id("fixture-response").build();
        var finish = ChatGenerationMetadata.builder().finishReason("stop").build();
        var longFrame = new ChatResponse(List.of(new Generation(new AssistantMessage("普通正文。".repeat(300),
                Map.of("fixture", true), List.of(tool)), finish)), metadata);
        var frames = guard.guard(Flux.just(longFrame)).collectList().block();
        assertThat(text(frames)).isEqualTo(longFrame.getResult().getOutput().getText());
        assertThat(frames.stream().flatMap(f -> f.getResults().stream()).flatMap(g -> g.getOutput().getToolCalls().stream()))
                .containsExactly(tool);
        assertThat(frames.get(frames.size() - 1).getMetadata()).isSameAs(metadata);
        assertThat(frames.get(frames.size() - 1).getResult().getMetadata()).isSameAs(finish);
    }

    @Test void checksEveryGenerationAndRetainsLongConfiguredSecrets() {
        var guard = new OutputModeration(List.of());
        var multi = new ChatResponse(List.of(new Generation(new AssistantMessage("safe")),
                new Generation(new AssistantMessage("已获取他人的个人信息"))));
        assertThat(text(guard.guard(Flux.just(multi)).collectList().block())).isEqualTo(OutputModeration.FALLBACK);
        assertThat(text(guard.guard(Flux.just(reply("a".repeat(262145)))).collectList().block())).isEqualTo(OutputModeration.FALLBACK);
        String secret = "fixture-" + "a".repeat(600);
        var secretGuard = new OutputModeration(List.of(secret));
        for (int split = 1; split < secret.length(); split++) {
            assertThat(text(secretGuard.guard(Flux.just(reply(secret.substring(0, split)), reply(secret.substring(split))))
                    .collectList().block())).isEqualTo(OutputModeration.FALLBACK);
        }
    }
}
