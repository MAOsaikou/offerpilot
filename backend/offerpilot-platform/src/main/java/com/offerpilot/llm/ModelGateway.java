package com.offerpilot.llm;

import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.llm.quota.LlmQuotaLimiter;
import com.offerpilot.llm.quota.LlmUserContext;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按档位选择模型，在供应商入口执行用户配额并累计已报告用量；回退与持久化审计日志待接入。
 */
@Component
public class ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    private final OfferPilotProperties properties;
    private final ObjectProvider<DeepSeekChatModel> deepseek;
    private final ObjectProvider<OpenAiChatModel> openai;
    private final Environment environment;
    private final LlmQuotaLimiter quotas;

    /** Compatibility constructor for isolated tests/live evaluations without business identity or Redis. */
    public ModelGateway(OfferPilotProperties properties, ObjectProvider<DeepSeekChatModel> deepseek,
                        ObjectProvider<OpenAiChatModel> openai, Environment environment) {
        this(properties, deepseek, openai, environment, null);
    }

    @Autowired
    public ModelGateway(OfferPilotProperties properties, ObjectProvider<DeepSeekChatModel> deepseek,
                        ObjectProvider<OpenAiChatModel> openai, Environment environment, LlmQuotaLimiter quotas) {
        this.properties = properties;
        this.deepseek = deepseek;
        this.openai = openai;
        this.environment = environment;
        this.quotas = quotas;
    }

    public ModelTier defaultTier() {
        return ModelTier.from(properties.llm().defaultTier());
    }

    public ChatModel chatModel(ModelTier tier) {
        ChatModel delegate = providerModel(tier);
        String capturedOwner = LlmUserContext.current();
        // Returning a raw provider would let ChatClient/synchronous callers bypass the gateway.
        return new ChatModel() {
            @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }
            @Override public ChatResponse call(Prompt prompt) {
                String owner = LlmUserContext.current() == null ? capturedOwner : LlmUserContext.current();
                var admission = admit(owner, tier);
                ChatResponse response = delegate.call(prompt);
                record(admission, tokens(response));
                return response;
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return guardedStream(tier, capturedOwner, () -> delegate.stream(prompt));
            }
        };
    }

    private ChatModel providerModel(ModelTier tier) {
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
        return guardedStream(tier, LlmUserContext.current(), () -> {
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
                    : OpenAiChatOptions.builder().model(modelName(tier)).temperature(temperature).streamUsage(true).build();
            return providerModel(tier).stream(new Prompt(messages, options));
        });
    }

    /** Preflight before SSE/creating work; admission still consumes atomically at each provider call. */
    public void checkQuota(String owner, ModelTier tier) {
        if (quotas != null) quotas.check(owner, tier, false);
    }

    private LlmQuotaLimiter.Admission admit(String owner, ModelTier tier) {
        return quotas == null ? null : quotas.check(owner, tier, true);
    }

    private Flux<ChatResponse> guardedStream(ModelTier tier, String capturedOwner,
            java.util.function.Supplier<Flux<ChatResponse>> provider) {
        return Flux.deferContextual(context -> {
            String owner = context.getOrDefault(LlmUserContext.KEY, capturedOwner);
            // Validate configuration before consuming a request; quotas always run before provider IO.
            requireAvailable(tier, false);
            var admission = admit(owner, tier);
            AtomicLong usage = new AtomicLong();
            return provider.get().timeout(configuration(tier).timeout())
                    .publishOn(Schedulers.boundedElastic())
                    // Stream usage is cumulative; repeated metadata must not be counted twice.
                    .doOnNext(response -> usage.accumulateAndGet(tokens(response), Math::max))
                    .doOnComplete(() -> record(admission, usage.get()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static long tokens(ChatResponse response) {
        var usage = response.getMetadata().getUsage();
        return usage == null || usage.getTotalTokens() == null ? 0 : Math.max(0, usage.getTotalTokens());
    }

    private void record(LlmQuotaLimiter.Admission admission, long tokens) {
        if (quotas != null) quotas.record(admission, tokens);
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
