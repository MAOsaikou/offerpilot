package com.offerpilot.llm.privacy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PiiMaskerTest {
    static final String ID = "110101199001010015";
    @Test void checksumAndNumericBoundariesLeaveBusinessFactsIntact() {
        String facts = "订单号：13800138000 jobId=13800138000 job_13800138000 202610101234567890 110101199001010016 110101199002310015 2026-10-10 20.5% 15k-25k v1.13800138000.2";
        var session = new PiiMasker.Session(List.of(facts));
        assertThat(session.mask(facts)).isEqualTo(facts);
        assertThat(session.mask("手机号13800138000，邮箱fictional@example.test，身份证" + ID))
                .isEqualTo("手机号[PHONE_1]，邮箱[EMAIL_1]，身份证[ID_CARD_1]");
        assertThat(session.mask("13800138000")).isEqualTo("[PHONE_1]");
        assertThat(session.restore("[PHONE_1] [EMAIL_1] [ID_CARD_1] [PHONE_99]"))
                .isEqualTo("13800138000 fictional@example.test " + ID + " [PHONE_99]");
    }
    @Test void avoidPreexistingPlaceholdersAndNeverShareMappings() {
        var first = new PiiMasker.Session(List.of("[PHONE_1] 13800138000"));
        assertThat(first.mask("[PHONE_1] 13800138000")).isEqualTo("[PHONE_1] [PHONE_2]");
        assertThat(first.restore("[PHONE_1] [PHONE_2]")).isEqualTo("[PHONE_1] 13800138000");
        assertThat(new PiiMasker.Session(List.of()).restore("[PHONE_2]")).isEqualTo("[PHONE_2]");
    }
    @Test void everySplitPointAndUnfinishedTokenFlushWithoutCorruptingMetadata() {
        var session = new PiiMasker.Session(List.of());
        session.mask("13800138000 fictional@example.test " + ID);
        for (String token : List.of("[PHONE_1]", "[EMAIL_1]", "[ID_CARD_1]")) {
            for (int i = 1; i < token.length(); i++) {
                var chunks = session.restore(Flux.just(chunk("前" + token.substring(0,i)), chunk(token.substring(i) + "后"))).collectList().block();
                assertThat(chunks.stream().map(c -> c.getResult().getOutput().getText()).reduce("", String::concat)).isEqualTo(session.restore("前" + token + "后"));
            }
        }
        assertThat(session.restore(Flux.just(chunk("尾部[PH"))).map(c -> c.getResult().getOutput().getText()).reduce("", String::concat).block()).isEqualTo("尾部[PH");
        var original = chunk("[PHONE_1]");
        var restored = session.restore(original);
        assertThat(restored.getMetadata()).isSameAs(original.getMetadata());
    }
    @Test void masksHistoryAndToolDataAsWellAsUserAndSystemText() {
        var prompt = new Prompt(List.of(new SystemMessage("上下文13800138000"), new UserMessage("fictional@example.test"),
                new AssistantMessage(ID), new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("call", "read", "13800138000")))));
        var masked = PiiMasker.forPrompt(prompt).mask(prompt);
        assertThat(masked.toString()).doesNotContain("13800138000", "fictional@example.test", ID);
        assertThat(((ToolResponseMessage) masked.getInstructions().get(3)).getResponses().get(0).responseData()).isEqualTo("[PHONE_1]");
    }
    private ChatResponse chunk(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
}
