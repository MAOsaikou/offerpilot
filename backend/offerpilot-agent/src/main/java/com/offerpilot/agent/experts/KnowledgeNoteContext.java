package com.offerpilot.agent.experts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.knowledge.application.KnowledgeNotesForChat;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Append evidence only to the result data consumed by Orchestrator's escaped user-message boundary. */
@Component
public class KnowledgeNoteContext {
    private final KnowledgeNotesForChat notes;
    private final ObjectMapper json;
    public KnowledgeNoteContext(KnowledgeNotesForChat notes, ObjectMapper json) { this.notes = notes; this.json = json; }
    public Mono<ExpertResult> attach(String owner, String query, ExpertResult result) {
        return Mono.fromCallable(() -> {
            var citations = notes.retrieve(owner, query);
            if (citations.isEmpty()) return result;
            try {
                var cards = new ArrayList<>(result.cards());
                cards.add(new ExpertResult.Card("knc_" + UUID.randomUUID(), "knowledge_note_citations",
                        Map.of("items", citations, "count", citations.size())));
                var evidence = citations.stream().map(citation -> Map.of("reference", citation.reference(),
                        "title", citation.title(), "category", citation.category(), "excerpt", citation.excerpt())).toList();
                return ExpertResult.cards(result.summary() + "\n用户知识笔记引用（用户自述证据）：" + json.writeValueAsString(evidence), List.copyOf(cards));
            } catch (JsonProcessingException ex) { throw new IllegalStateException("知识笔记引用暂时无法展示，请重试。", ex); }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
