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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按档位选择模型，在供应商入口执行用户配额并累计已报告用量；聊天关联调用写入持久化用量审计。
 */
@Component
public class ModelGateway {

    private static final String SCREEN_SESSION = ModelGateway.class.getName() + ".screen";
    private static final String EFFECTS_GATE = ModelGateway.class.getName() + ".effects";

    /** Gate all tool/memory effects, while model planning/generation can run concurrently. */
    public static reactor.core.publisher.Mono<Void> beforeEffects() {
        return reactor.core.publisher.Mono.deferContextual(context -> context.getOrDefault(EFFECTS_GATE,
                reactor.core.publisher.Mono.<Void>empty()));
    }

    public <T> Flux<T> withSafetyScreen(List<Message> messages, Flux<T> work) {
        return Flux.deferContextual(context -> {
            if (!environment.getProperty("offerpilot.agent.parallel-safety-screen", Boolean.class, true)) return work;
            String owner = context.getOrDefault(LlmUserContext.KEY, LlmUserContext.current());
            var session = new com.offerpilot.llm.safety.InputScreeningSession();
            var ready = reactor.core.publisher.Sinks.<Void>empty();
            var connection = UsageJournal.callable(() -> inputBoundary(owner, new Prompt(messages), session)).contextWrite(context)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(ignored -> ready.tryEmitEmpty(), ready::tryEmitError);
            return work.contextWrite(c -> c.put(SCREEN_SESSION, session).put(EFFECTS_GATE, ready.asMono()))
                    .doFinally(signal -> { connection.dispose(); session.close(); });
        });
    }

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    @Autowired(required = false)
    private UsageJournal usageJournal;

    private final OfferPilotProperties properties;
    private final Map<ModelTier, ChatModel> models = new ConcurrentHashMap<>();
    private final Map<ModelTier, OpenAiChatModel> visionModels = new ConcurrentHashMap<>();
    private final Environment environment;
    private final LlmQuotaLimiter quotas;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observations;
    @Autowired(required = false)
    private com.offerpilot.llm.privacy.PiiMaskingPreference privacy;

    public boolean piiMaskingEnabled(String owner) { return owner == null || privacy == null || privacy.enabled(owner); }
    public String privacyWarning(String owner, String text) {
        return !piiMaskingEnabled(owner) && com.offerpilot.llm.privacy.PiiMasker.detects(text)
                ? "检测到联系方式、身份证号或住址。当前已关闭文本脱敏，相关信息可能发送给模型；建议在设置中开启。" : null;
    }
    private com.offerpilot.llm.privacy.PiiMasker.Session masking(String owner, Prompt prompt) {
        return piiMaskingEnabled(owner) ? com.offerpilot.llm.privacy.PiiMasker.forPrompt(prompt) : null;
    }
    private Flux<ChatResponse> screenedStream(ModelTier tier, String capturedOwner, Prompt prompt,
            java.util.function.Function<Prompt, Flux<ChatResponse>> provider) {
        var capturedScope = UsageJournal.current();
        return Flux.deferContextual(context -> {
            String owner = context.getOrDefault(LlmUserContext.KEY, capturedOwner);
            requireAvailable(tier, false);
            checkQuota(owner, tier);
            java.util.function.Function<Prompt, Flux<ChatResponse>> outbound = screened -> {
                var session = masking(owner, screened);
                var response = provider.apply(session == null ? screened : session.mask(screened));
                return moderation().guard(session == null ? response : session.restore(response));
            };
            java.util.function.Function<Prompt, Flux<ChatResponse>> generate = screened ->
                    guardedStream(tier, owner, ignored -> outbound.apply(screened));
            if (!environment.getProperty("offerpilot.agent.parallel-safety-screen", Boolean.class, true)
                    || !environment.getProperty("offerpilot.llm.injection-classifier-enabled", Boolean.class, true))
                // Preserve serial admission ordering: reserve the generation request before classifying.
                return guardedStream(tier, owner, ignored -> outbound.apply(
                        UsageJournal.asScope(context.getOrDefault(UsageJournal.KEY, null),
                                () -> inputBoundary(owner, prompt, context.getOrDefault(SCREEN_SESSION, null)))));
            // Rules and the fixed policy are always installed before speculative provider IO.
            Prompt rules = com.offerpilot.llm.privacy.ModelInputBoundary.apply(prompt);
            var verdict = UsageJournal.callable(() -> inputBoundary(owner, prompt, context.getOrDefault(SCREEN_SESSION, null))).contextWrite(context)
                    .subscribeOn(Schedulers.boundedElastic());
            return com.offerpilot.llm.safety.ScreenedGeneration.release(verdict, generate.apply(rules),
                    screened -> screened.getInstructions().equals(rules.getInstructions()), generate);
        }).contextWrite(c -> capturedScope == null || c.hasKey(UsageJournal.KEY) ? c : c.put(UsageJournal.KEY, capturedScope))
          .subscribeOn(Schedulers.boundedElastic());
    }

    private final com.offerpilot.llm.safety.PromptInjectionClassifier injectionClassifier =
            new com.offerpilot.llm.safety.PromptInjectionClassifier(this::classifyInjection);
    private volatile ChatModel injectionModel;

    /** Same SC-01 screen for gateway inputs and import notices; owner comes from verified application context. */
    public List<String> detectInjection(String text) {
        return detectInjection(text, com.offerpilot.llm.safety.PromptInjectionClassifier.TIMEOUT);
    }

    private List<String> detectInjection(String text, java.time.Duration budget) {
        if (!environment.getProperty("offerpilot.llm.injection-classifier-enabled", Boolean.class, true))
            return com.offerpilot.llm.safety.PromptInjectionDetector.detect(text);
        var result = injectionClassifier.detectWithMetadata(text, budget);
        injectionClassificationCompleted(result);
        return result.categories();
    }

    /** Observation hook: immutable per-call outcomes only; never raw inputs or provider outputs. */
    protected void injectionClassificationCompleted(
            com.offerpilot.llm.safety.PromptInjectionClassifier.Detection result) {}

    private Prompt inputBoundary(String owner, Prompt prompt) {
        return inputBoundary(owner, prompt, null);
    }

    private Prompt inputBoundary(String owner, Prompt prompt, com.offerpilot.llm.safety.InputScreeningSession session) {
        long started = System.nanoTime();
        long deadline = started + com.offerpilot.llm.safety.PromptInjectionClassifier.TIMEOUT.toNanos();
        var screened = com.offerpilot.llm.privacy.ModelInputBoundary.apply(prompt, text -> LlmUserContext.asUser(owner,
                () -> session == null ? detectInjection(text, java.time.Duration.ofNanos(deadline - System.nanoTime()))
                        : session.detect(text, java.time.Duration.ofNanos(deadline - System.nanoTime()), this::detectInjection)));
        log.debug("AG02 phase=safety elapsedMs={}", (System.nanoTime() - started) / 1_000_000);
        return screened;
    }

    /** Dedicated provider entry: no recursion/tools/provider retries; classifier owns the bounded format retry. */
    protected reactor.core.publisher.Mono<String> classifyInjection(Prompt prompt) {
        requireAvailable(ModelTier.FLASH, false);
        var admission = admit(LlmUserContext.current(), ModelTier.FLASH);
        var session = masking(LlmUserContext.current(), prompt);
        var outbound = session == null ? prompt : session.mask(prompt);
        AtomicLong usage = new AtomicLong();
        var responses = injectionModel().stream(outbound);
        if (usageJournal != null && UsageJournal.current() != null)
            responses = usageJournal.observe(UsageJournal.current(), ModelTier.FLASH, modelName(ModelTier.FLASH),
                    responses.publishOn(Schedulers.boundedElastic()));
        return responses
                .doOnNext(response -> usage.accumulateAndGet(tokens(response), Math::max))
                .map(response -> response.getResult() == null || response.getResult().getOutput().getText() == null
                        ? "" : response.getResult().getOutput().getText())
                .reduce(new StringBuilder(), (body, part) -> {
                    if (body.length() + part.length() > 2048)
                        throw new com.offerpilot.llm.safety.PromptInjectionClassifier.InvalidOutputException();
                    return body.append(part);
                }).map(StringBuilder::toString)
                .doFinally(signal -> record(admission, usage.get()));
    }

    private synchronized ChatModel injectionModel() {
        if (injectionModel == null) {
            var retry = new RetryTemplate();
            retry.setRetryPolicy(new org.springframework.retry.policy.NeverRetryPolicy());
            var support = new DeepSeekRequestSupport("disabled", com.offerpilot.llm.safety.PromptInjectionClassifier.TIMEOUT);
            var api = org.springframework.ai.openai.api.OpenAiApi.builder().baseUrl(baseUrl(ModelTier.FLASH))
                    .apiKey(apiKey(ModelTier.FLASH)).completionsPath(completionsPath(ModelTier.FLASH));
            if ("deepseek".equalsIgnoreCase(configuration(ModelTier.FLASH).provider()))
                api.restClientBuilder(support.restClient()).webClientBuilder(support.webClient());
            injectionModel = OpenAiChatModel.builder().openAiApi(api.build()).retryTemplate(retry)
                    .observationRegistry(observations).defaultOptions(OpenAiChatOptions.builder()
                            .model(modelName(ModelTier.FLASH)).temperature(0.0).maxTokens(160).streamUsage(true).build()).build();
        }
        return injectionModel;
    }

    private volatile com.offerpilot.llm.safety.OutputModeration moderation;
    /** Built lazily from the configured provider keys and JWT secret; their values are never logged. */
    com.offerpilot.llm.safety.OutputModeration moderation() {
        if (moderation == null) {
            var secrets = new java.util.ArrayList<String>();
            for (String key : List.of("spring.ai.deepseek.api-key", "spring.ai.deepseek.chat.api-key", "spring.ai.openai.api-key",
                    "spring.ai.openai.chat.api-key", "spring.ai.openai.embedding.api-key", "offerpilot.security.jwt-secret",
                    "offerpilot.llm.providers.custom.api-key", "offerpilot.mcp.search-api-key",
                    "offerpilot.mcp.github-token", "offerpilot.mcp.server-api-key"))
                secrets.add(environment.getProperty(key, ""));
            moderation = new com.offerpilot.llm.safety.OutputModeration(secrets);
        }
        return moderation;
    }

    /** Compatibility constructor for isolated tests/live evaluations without business identity or Redis. */
    public ModelGateway(OfferPilotProperties properties, Environment environment) {
        this(properties, environment, null);
    }

    @Autowired
    public ModelGateway(OfferPilotProperties properties, Environment environment, LlmQuotaLimiter quotas,
                        ObjectProvider<RetryTemplate> retries, ObjectProvider<ObservationRegistry> observations) {
        this(properties, environment, quotas, retries.getIfAvailable(() -> RetryUtils.DEFAULT_RETRY_TEMPLATE),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    public ModelGateway(OfferPilotProperties properties, Environment environment, LlmQuotaLimiter quotas) {
        this(properties, environment, quotas, RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP);
    }

    public ModelGateway(OfferPilotProperties properties, Environment environment, LlmQuotaLimiter quotas, RetryTemplate retries) {
        this(properties, environment, quotas, retries, ObservationRegistry.NOOP);
    }

    private ModelGateway(OfferPilotProperties properties, Environment environment, LlmQuotaLimiter quotas,
                         RetryTemplate retryTemplate, ObservationRegistry observations) {
        this.properties = properties;
        this.environment = environment;
        this.quotas = quotas;
        this.retryTemplate = retryTemplate;
        this.observations = observations;
        for (String removed : List.of("LLM_MODEL_CHEAP", "LLM_PROVIDER_CHEAP", "LLM_VISION_CHEAP")) {
            if (environment.containsProperty(removed)) log.warn("{} is removed and ignored; configure FLASH instead", removed);
        }
    }

    /** Idle stream timeout; structured match also uses it as its complete generation/repair budget. */
    public java.time.Duration timeout(ModelTier tier) { return configuration(tier).timeout(); }

    public ModelTier defaultTier() {
        ModelTier tier = ModelTier.from(properties.llm().defaultTier());
        return tier == ModelTier.VISION_ALT ? ModelTier.FLASH : tier;
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
                prompt = inputBoundary(owner, prompt);
                var session = masking(owner, prompt);
                Prompt outbound = session == null ? prompt : session.mask(prompt);
                ChatResponse response = usageJournal == null || UsageJournal.current() == null ? delegate.call(outbound)
                        : usageJournal.observe(UsageJournal.current(),tier,modelName(tier),
                                Flux.defer(() -> Flux.just(delegate.call(outbound)))).blockLast();
                if (session != null) response = session.restore(response);
                record(admission, tokens(response));
                return moderation().check(response);
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return screenedStream(tier, capturedOwner, prompt, delegate::stream);
            }
        };
    }

    private ChatModel providerModel(ModelTier tier) {
        requireAvailable(tier, false);
        return models.computeIfAbsent(tier, selected -> {
            if (!"deepseek".equalsIgnoreCase(configuration(selected).provider())) return compatibleModel(selected);
            var support = new DeepSeekRequestSupport(configuration(selected).thinking(), configuration(selected).timeout());
            return DeepSeekChatModel.builder().retryTemplate(retryTemplate).observationRegistry(observations)
                    .deepSeekApi(org.springframework.ai.deepseek.api.DeepSeekApi.builder()
                            .baseUrl(baseUrl(selected)).apiKey(apiKey(selected))
                            .completionsPath(completionsPath(selected))
                            .restClientBuilder(support.restClient()).webClientBuilder(support.webClient()).build())
                    .defaultOptions(DeepSeekChatOptions.builder().model(modelName(selected)).temperature(0.3)
                            .maxTokens(configuration(selected).maxTokens()).build()).build();
        });
    }

    public Flux<ChatResponse> stream(ModelTier tier, List<Message> messages) {
        return stream(tier, messages, 0.3);
    }

    public Flux<ChatResponse> stream(ModelTier tier, List<Message> messages, double temperature) {
        boolean hasImages = messages.stream().anyMatch(message -> message instanceof
                org.springframework.ai.chat.messages.UserMessage user && !user.getMedia().isEmpty());
        ChatOptions options = hasImages || !"deepseek".equalsIgnoreCase(configuration(tier).provider())
                ? OpenAiChatOptions.builder().model(modelName(tier)).temperature(temperature)
                        .maxTokens(configuration(tier).maxTokens()).streamUsage(true).build()
                : DeepSeekChatOptions.builder().model(modelName(tier)).temperature(temperature)
                        .maxTokens(configuration(tier).maxTokens()).build();
        return screenedStream(tier, LlmUserContext.current(), new Prompt(messages, options), outbound -> {
            requireAvailable(tier, hasImages);
            return (hasImages ? compatibleModel(tier) : providerModel(tier)).stream(outbound);
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
            java.util.function.Function<String, Flux<ChatResponse>> provider) {
        var capturedScope = UsageJournal.current();
        return Flux.deferContextual(context -> {
            String owner = context.getOrDefault(LlmUserContext.KEY, capturedOwner);
            // Validate configuration before consuming a request; quotas always run before provider IO.
            requireAvailable(tier, false);
            var admission = admit(owner, tier);
            AtomicLong usage = new AtomicLong();
            Flux<ChatResponse> responses = provider.apply(owner).timeout(configuration(tier).timeout())
                    .publishOn(Schedulers.boundedElastic());
            UsageJournal.Scope scope = context.getOrDefault(UsageJournal.KEY, null);
            if (usageJournal != null && scope != null) responses = usageJournal.observe(scope, tier, modelName(tier), responses);
            return responses
                    // Stream usage is cumulative; repeated metadata must not be counted twice.
                    .doOnNext(response -> usage.accumulateAndGet(tokens(response), Math::max))
                    .doFinally(signal -> record(admission, usage.get()));
        }).contextWrite(c -> capturedScope == null || c.hasKey(UsageJournal.KEY) ? c : c.put(UsageJournal.KEY, capturedScope))
          .subscribeOn(Schedulers.boundedElastic());
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
                    case FLASH -> "快速"; case PRO -> "深度"; case VISION_ALT -> "视觉备选";
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
        return visionModels.computeIfAbsent(tier, selected -> {
            var api = org.springframework.ai.openai.api.OpenAiApi.builder()
                    .baseUrl(baseUrl(selected)).apiKey(apiKey(selected)).completionsPath(completionsPath(selected));
            if ("deepseek".equalsIgnoreCase(configuration(selected).provider())) {
                var support = new DeepSeekRequestSupport(configuration(selected).thinking(), configuration(selected).timeout());
                api.restClientBuilder(support.restClient()).webClientBuilder(support.webClient());
            }
            return OpenAiChatModel.builder().openAiApi(api.build()).retryTemplate(retryTemplate).observationRegistry(observations)
                    .defaultOptions(OpenAiChatOptions.builder().model(modelName(selected)).temperature(0.3)
                            .maxTokens(configuration(selected).maxTokens()).streamUsage(true).build()).build();
        });
    }

    private String completionsPath(ModelTier tier) {
        return environment.getProperty(prefix(tier) + ".completions-path",
                "deepseek".equalsIgnoreCase(configuration(tier).provider()) ? "/chat/completions" : "/v1/chat/completions");
    }

    public String modelName(ModelTier tier) {
        OfferPilotProperties.Llm.Tier configured = properties.llm().tiers().get(tier.name());
        if (configured != null && configured.model() != null && !configured.model().isBlank()) {
            return configured.model();
        }
        return switch (tier) {
            case FLASH -> "deepseek-flash";
            case PRO -> "deepseek-v4-pro";
            case VISION_ALT -> "deepseek-flash";
        };
    }

    public List<ModelTier> fallbackChain(ModelTier tier) {
        List<String> names = properties.llm().fallback().getOrDefault(tier.name(), List.of());
        return names.stream().map(ModelTier::from).toList();
    }
}
