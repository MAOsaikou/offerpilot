package com.offerpilot.llm;

import com.offerpilot.config.OfferPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;

import java.util.List;

/**
 * 按档位选择供应商和模型，并约束流式无响应超时；回退与持久化用量日志待接入。
 */
@Component
public class ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    private final OfferPilotProperties properties;
    private final ObjectProvider<DeepSeekChatModel> deepseek;
    private final ObjectProvider<OpenAiChatModel> openai;
    private final Environment environment;

    public ModelGateway(OfferPilotProperties properties, ObjectProvider<DeepSeekChatModel> deepseek,
                        ObjectProvider<OpenAiChatModel> openai, Environment environment) {
        this.properties = properties;
        this.deepseek = deepseek;
        this.openai = openai;
        this.environment = environment;
    }

    public ModelTier defaultTier() {
        return ModelTier.from(properties.llm().defaultTier());
    }

    public ChatModel chatModel(ModelTier tier) {
        boolean useDeepseek = "deepseek".equalsIgnoreCase(configuration(tier).provider());
        String prefix = useDeepseek ? "spring.ai.deepseek" : "spring.ai.openai";
        String key = environment.getProperty(prefix + ".chat.api-key",
                environment.getProperty(prefix + ".api-key", ""));
        if (key.isBlank() || key.contains("not-configured") || key.contains("xxxxxxxx")) {
            throw new BusinessException(ErrorCode.MODEL_NOT_CONFIGURED,
                    "请在 backend/.env.local 配置 " + (useDeepseek ? "DEEPSEEK_API_KEY" : "BAILIAN_API_KEY") + "，然后重启后端。");
        }
        ChatModel model = useDeepseek ? deepseek.getIfAvailable() : openai.getIfAvailable();
        // The default chat provider may be DeepSeek while resume OCR needs an OpenAI-compatible vision provider.
        if (model == null && !useDeepseek) {
            model = OpenAiChatModel.builder().openAiApi(org.springframework.ai.openai.api.OpenAiApi.builder()
                    .baseUrl(environment.getProperty("spring.ai.openai.base-url", "https://dashscope.aliyuncs.com/compatible-mode"))
                    .apiKey(key).build()).build();
        }
        if (model == null) {
            throw new BusinessException(ErrorCode.MODEL_NOT_CONFIGURED);
        }
        log.debug("Resolved ChatModel for tier={} model={}", tier, modelName(tier));
        return model;
    }

    public Flux<ChatResponse> stream(ModelTier tier, List<Message> messages) {
        return stream(tier, messages, 0.3);
    }

    public Flux<ChatResponse> stream(ModelTier tier, List<Message> messages, double temperature) {
        return Flux.defer(() -> {
            ChatOptions options = "deepseek".equalsIgnoreCase(configuration(tier).provider())
                    ? DeepSeekChatOptions.builder().model(modelName(tier)).temperature(temperature).build()
                    : OpenAiChatOptions.builder().model(modelName(tier)).temperature(temperature).build();
            return chatModel(tier).stream(new Prompt(messages, options));
        })
                .timeout(configuration(tier).timeout());
    }

    private OfferPilotProperties.Llm.Tier configuration(ModelTier tier) {
        var configured = properties.llm().tiers().get(tier.name());
        if (configured == null) throw new BusinessException(ErrorCode.MODEL_NOT_CONFIGURED);
        return configured;
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
