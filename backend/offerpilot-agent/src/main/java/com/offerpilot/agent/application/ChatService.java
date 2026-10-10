package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.orchestrator.Orchestrator;
import com.offerpilot.agent.web.ChatRequest;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.conversation.application.MessageCardBuffer;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Persistent multi-turn chat. Blocking database work stays off provider event loops. */
@Service
public class ChatService {
    private final ModelGateway gateway;
    private final ConversationService conversations;
    private final Orchestrator orchestrator;
    private final ObjectMapper json;
    private final String systemPrompt;
    private final MemoryChat memory;
    private static final ZoneId PROMPT_ZONE = ZoneId.of("Asia/Shanghai");
    private record Active(ConversationService.Turn turn, StringBuffer answer, Sinks.Empty<Void> signal, MessageCardBuffer cards) {}
    private final Map<String, Active> cancellations = new ConcurrentHashMap<>();

    public ChatService(ModelGateway gateway, ConversationService conversations,
                       @Value("classpath:prompts/chat/system.st") Resource prompt, Orchestrator orchestrator,
                       ObjectMapper json) throws IOException {
        this(gateway, conversations, prompt, orchestrator, json, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatService(ModelGateway gateway, ConversationService conversations,
                       @Value("classpath:prompts/chat/system.st") Resource prompt, Orchestrator orchestrator,
                       ObjectMapper json, MemoryChat memory) throws IOException {
        this.memory = memory;
        this.gateway = gateway;
        this.conversations = conversations;
        this.orchestrator = orchestrator;
        this.json = json;
        this.systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
    }

    public Flux<AgentEvent> chat(String owner, ChatRequest request) {
        String rawTier = request.options() == null ? null : request.options().modelTier();
        ModelTier selected = ModelTier.from(rawTier, gateway.defaultTier());
        ModelTier tier = selected == ModelTier.VISION_ALT ? gateway.defaultTier() : selected;
        if (request.attachments() != null && !request.attachments().isEmpty())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "当前仅支持文字聊天，附件解析尚未接入");
        // Ownership and concurrency errors remain ordinary HTTP errors before SSE starts.
        boolean explicitMemory = memory != null && com.offerpilot.memory.application.MemoryCandidate.explicit(request.content());
        if (!explicitMemory) gateway.checkQuota(owner, tier);
        ArrayList<Message> memoryPrompt = new ArrayList<>();
        var memoryFacts = memory == null ? List.<AgentEvent.MemoryFact>of() : memory.recall(owner, request.content(), memoryPrompt);
        var memoryCandidate = memory == null ? null : memory.candidate(owner, request.content());
        String model = gateway.modelName(tier);
        var turn = conversations.begin(owner, request.conversationId(), request.content(), request.mode(), model);
        var cancel = Sinks.<Void>empty();
        AtomicInteger seq = new AtomicInteger();
        AtomicReference<Usage> usage = new AtomicReference<>();
        AtomicInteger planningPromptTokens = new AtomicInteger();
        AtomicInteger planningCompletionTokens = new AtomicInteger();
        StringBuffer answer = new StringBuffer();
        MessageCardBuffer cards = new MessageCardBuffer(json);
        cancellations.put(turn.runId(), new Active(turn, answer, cancel, cards));
        long[] lastSaved = {0};
        ArrayList<Message> prompt = new ArrayList<>();
        prompt.add(new SystemMessage(systemPromptToday()));
        prompt.addAll(memoryPrompt);
        prompt.addAll(turn.history());
        prompt.add(new UserMessage(request.content()));
        AgentEvent first = new AgentEvent.RunStarted("run.started", seq.incrementAndGet(), turn.runId(), turn.startedAt(),
                turn.conversationId(), turn.messageId(), model, turn.mode());
        Flux<AgentEvent> response = Flux.defer(() -> gateway.stream(tier, prompt))
                .publishOn(Schedulers.boundedElastic())
                .<AgentEvent>handle((chunk, sink) -> {
                    Usage current = chunk.getMetadata().getUsage();
                    if (current != null && current.getTotalTokens() != null && current.getTotalTokens() > 0) usage.set(current);
                    if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                        String text = chunk.getResult().getOutput().getText();
                        if (text != null && !text.isEmpty()) {
                            if (answer.length() + text.length() > 64000) {
                                sink.error(new BusinessException(ErrorCode.MODEL_UNAVAILABLE, "回复超过长度限制，请缩小问题范围"));
                                return;
                            }
                            answer.append(text);
                            if (System.currentTimeMillis() - lastSaved[0] >= 1000) {
                                if (!conversations.checkpoint(turn, answer.toString())) { sink.complete(); return; }
                                lastSaved[0] = System.currentTimeMillis();
                            }
                            sink.next(new AgentEvent.TextDelta("text.delta", seq.incrementAndGet(), turn.runId(), Instant.now(), null, text));
                        }
                    }
                });
        Map<String, Object> context = request.options() == null || request.options().context() == null
                ? Map.of() : request.options().context();
        Flux<AgentEvent> work = Flux.defer(() -> orchestrator.run(turn.mode(), request.content(), turn.history(), tier,
                        turn.runId(), seq, prompt, u -> {
                            planningPromptTokens.addAndGet(u.getPromptTokens());
                            planningCompletionTokens.addAndGet(u.getCompletionTokens());
                        }, owner, context))
                .concatWith(response);
        if (explicitMemory) {
            AtomicReference<String> memoryReply = new AtomicReference<>(memory.enabled(owner)
                    ? "未生成可保存的记忆。请用「记住：」加一条不超过200字的个人事实，或在设置中手动添加；引用资料不能作为记忆。"
                    : "长期记忆已关闭，未保存。可在设置中开启或手动管理已有记忆。");
            work = (memoryCandidate == null ? Flux.<AgentEvent>empty() : memory.write(owner, turn.runId(), seq, memoryCandidate))
                    .doOnNext(event -> { if (event instanceof AgentEvent.ToolResult result) memoryReply.set(result.resultPreview()); })
                    .concatWith(Flux.defer(() -> {
                        answer.append(memoryReply.get());
                        return Flux.just(new AgentEvent.TextDelta("text.delta", seq.incrementAndGet(), turn.runId(), Instant.now(), null, memoryReply.get()));
                    }));
        } else if (memoryCandidate != null) {
            work = work.concatWith(Flux.defer(() -> answer.toString().isBlank() ? Flux.empty()
                    : memory.write(owner, turn.runId(), seq, memoryCandidate)));
        }
        work = work.takeUntilOther(cancel.asMono());
        Flux<AgentEvent> end = Flux.defer(() -> {
            if (answer.toString().isBlank() && !cancel.scan(reactor.core.Scannable.Attr.TERMINATED))
                return Flux.error(new BusinessException(ErrorCode.MODEL_UNAVAILABLE));
            Usage u = usage.get();
            int promptTokens = planningPromptTokens.get() + (u == null ? 0 : u.getPromptTokens());
            int completionTokens = planningCompletionTokens.get() + (u == null ? 0 : u.getCompletionTokens());
            boolean hasUsage = u != null || promptTokens + completionTokens > 0;
            Map<String, Object> savedUsage = !hasUsage ? null : Map.of("promptTokens", promptTokens, "completionTokens", completionTokens);
            String status = conversations.finish(turn, answer.toString(), "COMPLETED", savedUsage, null, cards.snapshot());
            Flux<AgentEvent> tokens = !hasUsage ? Flux.empty() : Flux.just(new AgentEvent.Usage("usage", seq.incrementAndGet(),
                    turn.runId(), Instant.now(), model, promptTokens, completionTokens, 0, false));
            return tokens.concatWith(Flux.defer(() -> Flux.just(new AgentEvent.RunCompleted("run.completed", seq.incrementAndGet(),
                    turn.runId(), Instant.now(), turn.messageId(), status, (int) Duration.between(turn.startedAt(), Instant.now()).toMillis()))));
        });
        Flux<AgentEvent> recalled = memoryFacts.isEmpty() ? Flux.empty() : Flux.defer(() -> Flux.just(
                new AgentEvent.MemoryUsed("memory.used", seq.incrementAndGet(), turn.runId(), Instant.now(), memoryFacts)));
        Flux<AgentEvent> streamed = Flux.just(first).concatWith(recalled).concatWith(work).handle((AgentEvent event, SynchronousSink<AgentEvent> sink) -> {
            if (event instanceof AgentEvent.UiCard card && "job_match".equals(card.cardType())
                    && com.offerpilot.conversation.application.MessageCards.parse(card.cardId(), card.cardType(), card.payload(), json) == null)
                throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "匹配报告未能展示：卡片内容无效或过长，请在岗位详情查看报告或重新分析。");
            if (event instanceof AgentEvent.UiCard card && !cards.accept(card.cardId(), card.cardType(), card.payload())) return;
            sink.next(event);
        });
        return streamed.concatWith(end)
                .takeUntilOther(reactor.core.publisher.Mono.delay(Duration.ofMinutes(100)).flatMap(ignored ->
                        reactor.core.publisher.Mono.error(new TimeoutException())))
                .onErrorResume(ex -> {
                    ErrorCode code = ex instanceof TimeoutException ? ErrorCode.MODEL_TIMEOUT : ErrorCode.MODEL_UNAVAILABLE;
                    String message = ex instanceof BusinessException business ? business.getMessage() : code.message();
                    if (ex instanceof BusinessException business) code = business.errorCode();
                    try { conversations.finish(turn, answer.toString(), "FAILED", null, message, cards.snapshot()); }
                    catch (RuntimeException persistenceFailure) { code = ErrorCode.DATABASE_UNAVAILABLE; message = code.message(); }
                    return Flux.just(new AgentEvent.ErrorEvent("error", seq.incrementAndGet(), turn.runId(), Instant.now(), code.code(), message, false));
                })
                .doFinally(signal -> {
                    cancellations.remove(turn.runId());
                    if (signal == reactor.core.publisher.SignalType.CANCEL) {
                        try { conversations.finish(turn, answer.toString(), "CANCELLED", null, null, cards.snapshot()); }
                        catch (RuntimeException ex) {
                            org.slf4j.LoggerFactory.getLogger(ChatService.class).warn("Unable to save interrupted run {}", turn.runId());
                        }
                    }
                }).contextWrite(quotaContext -> com.offerpilot.llm.quota.LlmUserContext.withUser(quotaContext, owner));
    }

    public void cancel(String owner, String runId) {
        var active = cancellations.get(runId);
        conversations.cancel(owner, runId, active == null ? null : active.answer().toString(),
                active == null ? List.of() : active.cards().snapshot());
        if (active != null) {
            active.signal().tryEmitEmpty();
        }
    }

    /**
     * 解析模板里的 {current_date}。
     *
     * 必须每次请求重新解析而不是在构造时算一次：服务可能连续运行多天，
     * 那样第二天仍会告诉模型昨天的日期。
     */
    private String systemPromptToday() {
        return systemPrompt.replace("{current_date}", LocalDate.now(PROMPT_ZONE).toString());
    }
}
