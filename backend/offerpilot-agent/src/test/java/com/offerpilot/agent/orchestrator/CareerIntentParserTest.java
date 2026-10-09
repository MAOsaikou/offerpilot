package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CareerIntentParserTest {
    PlanStep step(Map<String,Object> args) { return new PlanStep("s1", "职业", ExpertType.CAREER_DISCOVERY, args, List.of()); }
    @Test void defaultsToRecommendAndOnlyExplicitCommandsOrStructuredSelectionsAdopt() {
        assertThat(CareerIntentParser.prepare(step(Map.of()), "我不知道适合什么", Map.of()).inputs()).containsEntry("action", "recommend");
        assertThat(CareerIntentParser.prepare(step(Map.of()), "请将「软件工程师」设为目标职业", Map.of()).inputs())
                .containsExactlyInAnyOrderEntriesOf(Map.of("action", "setCareerTarget", "name", "软件工程师"));
        assertThat(CareerIntentParser.prepare(step(Map.of()), "把数据分析师设为目标", Map.of()).inputs()).containsEntry("name", "数据分析师");
        assertThat(CareerIntentParser.prepare(step(Map.of("action", "setCareerTarget", "occupationId", "model-guessed")),
                "帮我设置目标", Map.of()).inputs()).containsEntry("action", "clarify");
        assertThat(CareerIntentParser.prepare(step(Map.of("occupationId", "model-guessed")), "选这个",
                Map.of("careerAction", "setCareerTarget", "occupationId", "chosen", "owner", "attacker")).inputs())
                .containsExactlyInAnyOrderEntriesOf(Map.of("action", "setCareerTarget", "occupationId", "chosen"));
        assertThat(CareerIntentParser.prepare(step(Map.of()), "选这个", Map.of("careerAction", "setCareerTarget")).inputs())
                .containsEntry("action", "clarify");
    }
}
