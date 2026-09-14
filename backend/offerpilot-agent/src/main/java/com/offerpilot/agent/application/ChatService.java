package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.web.ChatRequest;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 单轮文字聊天应用服务；持久化和专家编排在后续阶段接入。 */
@Service
public class ChatService {
    private final ModelGateway gateway;
    private final String systemPrompt;

    public ChatService(ModelGateway gateway, @Value("classpath:prompts/chat/system.st") Resource prompt) throws IOException {
        this.gateway = gateway;
        this.systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
    }

    public Flux<AgentEvent> chat(ChatRequest request) {
        String rawTier = request.options() == null ? null : request.options().modelTier();
        ModelTier tier;
        try {
            tier = rawTier == null || rawTier.isBlank() ? gateway.defaultTier() : ModelTier.from(rawTier);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知模型档位");
        }
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "当前仅支持文字聊天，附件解析尚未接入");
        }
        return Flux.defer(() -> {
            String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                    ? IdGenerator.next("conv") : request.conversationId();
            String runId = IdGenerator.next("run");
            String messageId = IdGenerator.next("msg");
            String model = gateway.modelName(tier);
            Instant started = Instant.now();
            AtomicInteger seq = new AtomicInteger();
            AtomicReference<Usage> usage = new AtomicReference<>();
            StringBuilder answer = new StringBuilder();
            AgentEvent first = new AgentEvent.RunStarted("run.started", seq.incrementAndGet(), runId, started,
                    conversationId, messageId, model, request.mode() == null ? AgentMode.AUTO : request.mode());
            Flux<AgentEvent> response = Flux.defer(() -> gateway.stream(tier,
                            List.of(new SystemMessage(systemPrompt), new UserMessage(request.content()))))
                    .handle((chunk, sink) -> {
                        Usage current = chunk.getMetadata().getUsage();
                        if (current != null && current.getTotalTokens() != null && current.getTotalTokens() > 0) {
                            usage.set(current);
                        }
                        if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                            String text = chunk.getResult().getOutput().getText();
                            if (text != null && !text.isEmpty()) {
                                answer.append(text);
                                sink.next(new AgentEvent.TextDelta("text.delta", seq.incrementAndGet(), runId,
                                        Instant.now(), null, text));
                            }
                        }
                    });
            Flux<AgentEvent> end = Flux.defer(() -> {
                if (answer.toString().isBlank()) return Flux.error(new BusinessException(ErrorCode.MODEL_UNAVAILABLE));
                Usage u = usage.get();
                Flux<AgentEvent> tokens = u == null ? Flux.empty() : Flux.just(new AgentEvent.Usage("usage",
                        seq.incrementAndGet(), runId, Instant.now(), model, u.getPromptTokens(),
                        u.getCompletionTokens(), 0, false));
                return tokens.concatWith(Flux.defer(() -> Flux.just(new AgentEvent.RunCompleted("run.completed",
                        seq.incrementAndGet(), runId, Instant.now(), messageId, "COMPLETED",
                        (int) (Instant.now().toEpochMilli() - started.toEpochMilli())))));
            });
            return Flux.just(first).concatWith(response).concatWith(end).onErrorResume(ex -> {
                ErrorCode code = ex instanceof TimeoutException ? ErrorCode.MODEL_TIMEOUT : ErrorCode.MODEL_UNAVAILABLE;
                String message = code.message();
                if (ex instanceof BusinessException business) {
                    code = business.errorCode();
                    message = business.getMessage();
                }
                // 供应商原始异常可能包含请求、凭据或响应正文，不透传。
                return Flux.just(new AgentEvent.ErrorEvent("error", seq.incrementAndGet(), runId,
                        Instant.now(), code.code(), message, false));
            });
        });
    }
}
