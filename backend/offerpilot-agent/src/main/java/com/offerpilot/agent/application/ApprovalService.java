package com.offerpilot.agent.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.conversation.application.ConversationService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance approval waits. No thread or database transaction is held while waiting. */
@Service
public class ApprovalService {
    public record Decision(String decision, Map<String, Object> args, String reason) {}
    public static final Duration WINDOW = Duration.ofMinutes(10);
    private final ConversationService conversations;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public ApprovalService(ConversationService conversations) { this.conversations = conversations; }

    public final class Ticket {
        private final String id = UUID.randomUUID().toString();
        private final String owner;
        private final String action;
        private final Map<String, Object> args;
        private final Instant expiresAt;
        private final Sinks.One<Decision> signal = Sinks.one();
        private Decision resolved;

        private Ticket(String owner, String action, Map<String, Object> args, Duration window) {
            this.owner = owner;
            this.action = action;
            this.args = Map.copyOf(args);
            this.expiresAt = Instant.now().plus(window);
        }
        public String id() { return id; }
        public Map<String, Object> args() { return args; }
        public Instant expiresAt() { return expiresAt; }
        public Mono<Decision> await() {
            Duration remaining = Duration.between(Instant.now(), expiresAt);
            return signal.asMono().timeout(remaining.isNegative() ? Duration.ZERO : remaining, Mono.fromSupplier(this::expire))
                    .doOnCancel(this::expire);
        }
        private synchronized Decision expire() {
            if (resolved == null) {
                resolved = new Decision("TIMEOUT", args, "审批已超时");
                signal.tryEmitValue(resolved);
            }
            return resolved;
        }
        private synchronized Decision decide(String owner, Decision body) {
            if (!this.owner.equals(owner)) throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
            if (!Instant.now().isBefore(expiresAt)) expire();
            if (resolved != null) throw new BusinessException(ErrorCode.APPROVAL_EXPIRED, "审批已处理或已超时");
            if (body == null || !java.util.Set.of("APPROVE", "REJECT").contains(body.decision() == null ? "" : body.decision())
                    || (body.reason() != null && body.reason().length() > 2000))
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审批决策无效");
            var finalArgs = new java.util.LinkedHashMap<>(args);
            if (body.args() != null) {
                if (!java.util.Set.of("status", "note").containsAll(body.args().keySet()))
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只能编辑状态和备注");
                finalArgs.putAll(body.args());
            }
            BoardWriteTools.validate(action, finalArgs);
            resolved = new Decision(body.decision(), Map.copyOf(finalArgs), body.reason());
            signal.tryEmitValue(resolved);
            return resolved;
        }
    }

    public Ticket request(String owner, String runId, String action, Map<String, Object> args) {
        conversations.extendApprovalLease(owner, runId);
        return request(owner, runId, action, args, WINDOW);
    }

    // Duration overload keeps timeout tests deterministic without waiting ten minutes.
    Ticket request(String owner, String runId, String action, Map<String, Object> args, Duration window) {
        BoardWriteTools.validate(action, args);
        Ticket ticket = new Ticket(owner, action, args, window);
        String key = runId + ":" + ticket.id();
        tickets.put(key, ticket);
        // Retain resolved tombstones for duplicate POSTs, then bound memory usage.
        Mono.delay(window.plus(Duration.ofMinutes(10))).subscribe(ignored -> tickets.remove(key, ticket));
        return ticket;
    }

    public Decision resolve(String owner, String runId, String approvalId, Decision body) {
        conversations.requireRunOwner(owner, runId);
        Ticket ticket = tickets.get(runId + ":" + approvalId);
        if (ticket == null) throw new BusinessException(ErrorCode.RUN_NOT_FOUND, "审批不存在");
        return ticket.decide(owner, body);
    }
}
