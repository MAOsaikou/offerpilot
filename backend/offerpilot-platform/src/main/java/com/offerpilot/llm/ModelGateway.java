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
        requireAvailable(tier, false);
        ChatModel model = useDeepseek ? deepseek.getIfAvailable() : compatibleModel(tier);
        if (model == null) model = org.springframework.ai.deepseek.DeepSeekChatModel.builder()
                .deepSeekApi(org.springframework.ai.deepseek.api.DeepSeekApi.builder()
                        .baseUrl(baseUrl(tier)).apiKey(apiKey(tier)).build()).build();
        log.debug("Resolved ChatModel for tier={} model={}", tier, modelName(tier));
        return model;
    }

    public Flux<ChatResponse> stream(ModelTier tier, List<Message> messages) {
        return stream(tier, messages, 0.3);
    }

    public Flux<ChatResponse> stream(ModelTier tier, List<Message> messages, double temperature) {
        return Flux.defer(() -> {
            // Spring AI 1.0 的 DeepSeek 适配器不处理图片；图文请求使用同一供应商的兼容接口。
            boolean hasImages = messages.stream().anyMatch(message -> message instanceof
                    org.springframework.ai.chat.messages.UserMessage user && !user.getMedia().isEmpty());
            requireAvailable(tier, hasImages);
            if (hasImages) {
                return compatibleModel(tier).stream(new Prompt(messages,
                        OpenAiChatOptions.builder().model(modelName(tier)).temperature(temperature).streamUsage(true).build()));
            }
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

    public record ModelOption(String id, String label, String provider, String model, boolean supportsVision, boolean configured) {}

    public List<ModelOption> catalog() {
        return java.util.Arrays.stream(ModelTier.values()).filter(tier -> properties.llm().tiers().containsKey(tier.name()))
                .map(tier -> new ModelOption(tier.name(), switch (tier) {
                    case FLASH -> "快捷模型"; case CHEAP -> "常用模型"; case PRO -> "高质量模型"; case VISION_ALT -> "视觉备选";
                }, configuration(tier).provider(), modelName(tier), supportsVision(tier), configured(tier))).toList();
    }

    public boolean supportsVision(ModelTier tier) { return configuration(tier).supportsVision(); }

    public void requireAvailable(ModelTier tier, boolean vision) {
        if (!configured(tier)) throw new BusinessException(ErrorCode.MODEL_NOT_CONFIGURED, "该模型尚未配置完整，请在后端配置供应商地址、API Key 和模型名");
        if (vision && !supportsVision(tier)) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选模型未配置图片能力，请选择支持图片的模型");
    }

    private boolean configured(ModelTier tier) {
        String key = apiKey(tier), base = baseUrl(tier);
        return !key.isBlank() && !key.contains("not-configured") && !key.contains("xxxxxxxx")
                && !modelName(tier).isBlank() && (base.startsWith("https://") || base.startsWith("http://"));
    }

    private String prefix(ModelTier tier) {
        return switch (configuration(tier).provider().toLowerCase(java.util.Locale.ROOT)) {
            case "deepseek" -> "spring.ai.deepseek";
            case "bailian" -> "spring.ai.openai";
            default -> "offerpilot.llm.providers." + configuration(tier).provider();
        };
    }
    private String apiKey(ModelTier tier) {
        return environment.getProperty(prefix(tier) + ".chat.api-key", environment.getProperty(prefix(tier) + ".api-key", ""));
    }
    private String baseUrl(ModelTier tier) { return environment.getProperty(prefix(tier) + ".base-url", ""); }

    private OpenAiChatModel compatibleModel(ModelTier tier) {
        return OpenAiChatModel.builder().openAiApi(org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl(tier)).apiKey(apiKey(tier))
                .completionsPath(environment.getProperty(prefix(tier) + ".completions-path",
                        "deepseek".equalsIgnoreCase(configuration(tier).provider()) ? "/chat/completions" : "/v1/chat/completions"))
                .build()).build();
    }

    public String modelName(ModelTier tier) {
        OfferPilotProperties.Llm.Tier configured = properties.llm().tiers().get(tier.name());
        if (configured != null && configured.model() != null && !configured.model().isBlank()) {
            return configured.model();
        }
        return switch (tier) {
            case FLASH -> "qwen3.8-flash";
            case CHEAP -> "deepseek-flash";
            case PRO -> "qwen3.8-max";
            case VISION_ALT -> "deepseek-flash";
        };
    }

    public List<ModelTier> fallbackChain(ModelTier tier) {
        List<String> names = properties.llm().fallback().getOrDefault(tier.name(), List.of());
        return names.stream().map(ModelTier::from).toList();
    }
}
