package com.offerpilot.llm;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ModelTierTest {
    @Test void foldsLegacyAliasesAndHandlesDefaultWithoutRejectingOldClients() {
        for (String raw : new String[]{"CHEAP", "cheap", " economy ", "经济", "BALANCED", "balanced", "FLASH", "快速"})
            assertThat(ModelTier.from(raw, ModelTier.PRO)).isEqualTo(ModelTier.FLASH);
        for (String raw : new String[]{null, "", " ", "future-model"})
            assertThat(ModelTier.from(raw, ModelTier.PRO)).isEqualTo(ModelTier.PRO);
        assertThat(ModelTier.from("unknown")).isEqualTo(ModelTier.FLASH);
        assertThat(ModelTier.from("pro")).isEqualTo(ModelTier.PRO);
        assertThat(ModelTier.from("VISION")).isEqualTo(ModelTier.VISION_ALT);
        assertThat(ModelTier.values()).containsExactly(ModelTier.FLASH, ModelTier.PRO, ModelTier.VISION_ALT);
    }
}
