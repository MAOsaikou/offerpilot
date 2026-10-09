package com.offerpilot.llm;

import com.offerpilot.config.OfferPilotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class LlmPropertiesTest {
    @Test void bindsTierCanonicalConstructorAndConfigurableThinkingBudget() {
        var source = new MapConfigurationPropertySource(Map.of(
                "offerpilot.llm.tiers.FLASH.provider", "deepseek",
                "offerpilot.llm.tiers.FLASH.model", "deepseek-flash",
                "offerpilot.llm.tiers.FLASH.supports-vision", "true",
                "offerpilot.llm.tiers.FLASH.thinking", "enabled",
                "offerpilot.llm.tiers.FLASH.max-tokens", "16384",
                "offerpilot.llm.tiers.PRO.provider", "deepseek",
                "offerpilot.llm.tiers.PRO.model", "deepseek-v4-pro"));
        var llm = new Binder(source).bind("offerpilot.llm", Bindable.of(OfferPilotProperties.Llm.class)).get();
        assertThat(llm.tiers().get("FLASH").thinking()).isEqualTo("enabled");
        assertThat(llm.tiers().get("FLASH").maxTokens()).isEqualTo(16384);
        assertThat(llm.tiers().get("FLASH").supportsVision()).isTrue();
        assertThat(llm.tiers().get("PRO").thinking()).isEqualTo("disabled");
        assertThat(llm.defaultTier()).isEqualTo("FLASH");
    }
}
