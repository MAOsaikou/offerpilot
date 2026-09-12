package com.offerpilot.llm;

import com.offerpilot.config.OfferPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型网关雏形：按档位选择 ChatModel，后续补齐回退链、超时与用量日志。
 */
@Component
public class ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    private final OfferPilotProperties properties;
    private final ObjectProvider<ChatModel> chatModels;

    public ModelGateway(OfferPilotProperties properties, ObjectProvider<ChatModel> chatModels) {
        this.properties = properties;
        this.chatModels = chatModels;
    }

    public ModelTier defaultTier() {
        return ModelTier.from(properties.llm().defaultTier());
    }

    public ChatModel chatModel(ModelTier tier) {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("没有可用的 ChatModel，请检查 BAILIAN_API_KEY / DEEPSEEK_API_KEY");
        }
        log.debug("Resolved ChatModel for tier={} model={}", tier, modelName(tier));
        return model;
    }

    public String modelName(ModelTier tier) {
        OfferPilotProperties.Llm.Tier configured = properties.llm().tiers().get(tier.name());
        if (configured != null && configured.model() != null && !configured.model().isBlank()) {
            return configured.model();
        }
        return switch (tier) {
            case FLASH -> "qwen3.8-flash";
            case CHEAP -> "deepseek-v4-flash";
            case PRO -> "qwen3.8-max";
            case VISION_ALT -> "glm-5.3-flash";
        };
    }

    public List<ModelTier> fallbackChain(ModelTier tier) {
        List<String> names = properties.llm().fallback().getOrDefault(tier.name(), List.of());
        return names.stream().map(ModelTier::from).toList();
    }
}
