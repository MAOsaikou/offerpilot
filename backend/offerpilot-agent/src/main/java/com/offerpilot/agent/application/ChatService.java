package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.web.ChatRequest;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.conversation.application.ConversationService;
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
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private final String systemPrompt;
    private record Active(ConversationService.Turn turn, StringBuffer answer, Sinks.Empty<Void> signal) {}
    private final Map<String, Active> cancellations = new ConcurrentHashMap<>();

    public ChatService(ModelGateway gateway, ConversationService conversations,
                       @Value("classpath:prompts/chat/system.st") Resource prompt) throws IOException {
        this.gateway = gateway;
        this.conversations = conversations;
        this.systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
    }

    public Flux<AgentEvent> chat(String owner, ChatRequest request) {
        String rawTier = request.options() == null ? null : request.options().modelTier();
        ModelTier tier;
        try { tier = rawTier == null || rawTier.isBlank() ? gateway.defaultTier() : ModelTier.from(rawTier); }
        catch (IllegalArgumentException ex) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知模型档位"); }
        if (request.attachments() != null && !request.attachments().isEmpty())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "当前仅支持文字聊天，附件解析尚未接入");
        // Ownership and concurrency errors remain ordinary HTTP errors before SSE starts.
        String model = gateway.modelName(tier);
        var turn = conversations.begin(owner, request.conversationId(), request.content(), request.mode(), model);
        var cancel = Sinks.<Void>empty();
        AtomicInteger seq = new AtomicInteger();
        AtomicReference<Usage> usage = new AtomicReference<>();
        StringBuffer answer = new StringBuffer();
        cancellations.put(turn.runId(), new Active(turn, answer, cancel));
        long[] lastSaved = {0};
        ArrayList<Message> prompt = new ArrayList<>();
        prompt.add(new SystemMessage(systemPrompt));
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
                }).takeUntilOther(cancel.asMono());
        Flux<AgentEvent> end = Flux.defer(() -> {
            if (answer.toString().isBlank() && !cancel.scan(reactor.core.Scannable.Attr.TERMINATED))
                return Flux.error(new BusinessException(ErrorCode.MODEL_UNAVAILABLE));
            Usage u = usage.get();
            Map<String, Object> savedUsage = u == null ? null : Map.of("promptTokens", u.getPromptTokens(), "completionTokens", u.getCompletionTokens());
            String status = conversations.finish(turn, answer.toString(), "COMPLETED", savedUsage, null);
            Flux<AgentEvent> tokens = u == null ? Flux.empty() : Flux.just(new AgentEvent.Usage("usage", seq.incrementAndGet(),
                    turn.runId(), Instant.now(), model, u.getPromptTokens(), u.getCompletionTokens(), 0, false));
            return tokens.concatWith(Flux.defer(() -> Flux.just(new AgentEvent.RunCompleted("run.completed", seq.incrementAndGet(),
                    turn.runId(), Instant.now(), turn.messageId(), status, (int) Duration.between(turn.startedAt(), Instant.now()).toMillis()))));
        });
        return Flux.just(first).concatWith(response).concatWith(end)
                .takeUntilOther(reactor.core.publisher.Mono.delay(Duration.ofMinutes(10)).flatMap(ignored ->
                        reactor.core.publisher.Mono.error(new TimeoutException())))
                .onErrorResume(ex -> {
                    ErrorCode code = ex instanceof TimeoutException ? ErrorCode.MODEL_TIMEOUT : ErrorCode.MODEL_UNAVAILABLE;
                    String message = ex instanceof BusinessException business ? business.getMessage() : code.message();
                    if (ex instanceof BusinessException business) code = business.errorCode();
                    try { conversations.finish(turn, answer.toString(), "FAILED", null, message); }
                    catch (RuntimeException persistenceFailure) { code = ErrorCode.DATABASE_UNAVAILABLE; message = code.message(); }
                    return Flux.just(new AgentEvent.ErrorEvent("error", seq.incrementAndGet(), turn.runId(), Instant.now(), code.code(), message, false));
                })
                .doFinally(signal -> {
                    cancellations.remove(turn.runId());
                    if (signal == reactor.core.publisher.SignalType.CANCEL) {
                        try { conversations.finish(turn, answer.toString(), "CANCELLED", null, null); }
                        catch (RuntimeException ex) {
                            org.slf4j.LoggerFactory.getLogger(ChatService.class).warn("Unable to save interrupted run {}", turn.runId());
                        }
                    }
                });
    }

    public void cancel(String owner, String runId) {
        var active = cancellations.get(runId);
        conversations.cancel(owner, runId, active == null ? null : active.answer().toString());
        if (active != null) {
            active.signal().tryEmitEmpty();
        }
    }
}
