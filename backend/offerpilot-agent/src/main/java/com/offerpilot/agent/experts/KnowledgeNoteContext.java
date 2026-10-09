package com.offerpilot.agent.experts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.knowledge.application.KnowledgeNotesForChat;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
                return ExpertResult.cards(result.summary() + "\n用户知识笔记引用（用户自述证据）：" + json.writeValueAsString(citations), result.cards());
            } catch (JsonProcessingException ex) { return result; }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
