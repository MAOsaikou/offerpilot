package com.offerpilot.agent.experts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.knowledge.application.KnowledgeNotesForChat;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import com.offerpilot.llm.ChatLatency;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Append evidence only to the result data consumed by Orchestrator's escaped user-message boundary. */
@Component
public class KnowledgeNoteContext {
    private static final String PREFETCH = KnowledgeNoteContext.class.getName();
    private record Prefetch(String owner, String query, Mono<List<KnowledgeNotesForChat.Citation>> result) {}
    private boolean parallel = true;
    @org.springframework.beans.factory.annotation.Value("${offerpilot.agent.parallel-note-retrieval:true}")
    void setParallel(boolean parallel) { this.parallel = parallel; }
    private final KnowledgeNotesForChat notes;
    private final ObjectMapper json;
    public KnowledgeNoteContext(KnowledgeNotesForChat notes, ObjectMapper json) { this.notes = notes; this.json = json; }

    /** Read-only evidence, shared only by exact owner/query within this subscription. No tool is executed. */
    public <T> Flux<T> withPrefetch(String owner, String query, Flux<T> work) {
        if (!parallel || query == null || query.isBlank()) return work;
        String bounded = com.offerpilot.knowledge.application.KnowledgeNoteSearch.bounded(query, 2000).strip();
        return Flux.deferContextual(context -> Flux.using(() -> {
            var replay = retrieve(owner, bounded).flux().contextWrite(context).replay(1);
            // Start independently of the planner; using disposes the read on cancellation/error/terminal.
            return new Pending(new Prefetch(owner, bounded, replay.next()), replay.connect());
        }, pending -> work.contextWrite(c -> c.put(PREFETCH, pending.prefetch())),
                pending -> pending.connection().dispose()));
    }

    private record Pending(Prefetch prefetch, reactor.core.Disposable connection) {}

    private Mono<List<KnowledgeNotesForChat.Citation>> retrieve(String owner, String query) {
        return ChatLatency.mono("note-retrieval", com.offerpilot.llm.UsageJournal.callable(() -> notes.retrieve(owner, query))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<List<KnowledgeNotesForChat.Citation>> evidence(String owner, String query) {
        return Mono.deferContextual(context -> {
            Prefetch prefetched = context.getOrDefault(PREFETCH, null);
            return prefetched != null && prefetched.owner().equals(owner) && prefetched.query().equals(query)
                    ? prefetched.result() : retrieve(owner, query);
        });
    }

    public Mono<ExpertResult> attach(String owner, String query, ExpertResult result) {
        return attach(owner, query, Mono.just(result));
    }

    public Mono<ExpertResult> attach(String owner, String query, Mono<ExpertResult> work) {
        if (!parallel) return work.flatMap(result -> evidence(owner, query).map(citations -> decorate(result, citations)));
        // Both are independent reads; keep every citation and the original tool result.
        return Mono.zip(work, evidence(owner, query), this::decorate);
    }

    private ExpertResult decorate(ExpertResult result, List<KnowledgeNotesForChat.Citation> citations) {
        if (citations.isEmpty()) return result;
        try {
            var cards = new ArrayList<>(result.cards());
            cards.add(new ExpertResult.Card("knc_" + UUID.randomUUID(), "knowledge_note_citations",
                    Map.of("items", citations, "count", citations.size())));
            var evidence = citations.stream().map(citation -> Map.of("reference", citation.reference(),
                    "title", citation.title(), "category", citation.category(), "excerpt", citation.excerpt())).toList();
            return ExpertResult.cards(result.summary() + "\n用户知识笔记引用（用户自述证据）：" + json.writeValueAsString(evidence), List.copyOf(cards));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("知识笔记引用暂时无法展示，请重试。", ex); }
    }
}
