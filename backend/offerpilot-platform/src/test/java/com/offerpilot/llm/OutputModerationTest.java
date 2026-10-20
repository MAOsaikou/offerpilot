package com.offerpilot.llm;
import com.offerpilot.llm.safety.OutputModeration;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class OutputModerationTest {
    private ChatResponse reply(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    @Test void blocksEverySplitWithoutReleasingPrefixAndCountsOnce() {
        var hits=new ArrayList<String>();
        var guard=new OutputModeration(List.of("fixture-secret-value-1234"),hits::add);
        for(String text:List.of("prefix fixture-secret-value-1234 suffix","已读取其他用户的简历和电话","你的身份证号是123456789012345678","OfferPilot 安全边界")) {
            for(int i=1;i<text.length();i++) {
                hits.clear();
                var output=guard.guard(Flux.just(reply(text.substring(0,i)),reply(text.substring(i)))).collectList().block();
                assertThat(output).hasSize(1);
                assertThat(output.get(0).getResult().getOutput().getText()).isEqualTo(OutputModeration.FALLBACK);
                assertThat(hits).hasSize(1);
            }
            assertThatThrownBy(() -> guard.check(reply(text))).isInstanceOf(BusinessException.class).hasMessage(OutputModeration.FALLBACK);
        }
    }
    @Test void safeRepliesAreUnchangedAndOtherGenerationsAreChecked() {
        var guard=new OutputModeration(List.of());
        var safe=reply("请核对简历中的真实经历，不要编造个人信息。");
        assertThat(guard.check(safe)).isSameAs(safe);
        assertThat(guard.guard(Flux.just(safe)).blockFirst()).isSameAs(safe);
        var multi=new ChatResponse(List.of(new Generation(new AssistantMessage("safe")),new Generation(new AssistantMessage("已获取他人的个人信息"))));
        assertThat(guard.guard(Flux.just(multi)).blockFirst().getResult().getOutput().getText()).isEqualTo(OutputModeration.FALLBACK);
        assertThat(guard.guard(Flux.just(reply("a".repeat(262145)))).blockFirst().getResult().getOutput().getText()).isEqualTo(OutputModeration.FALLBACK);
    }
}
