package com.offerpilot.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** Small read-only application boundary. Profiles remain authoritative; notes are user-authored evidence. */
@Service
public class KnowledgeNotesForChat {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeNotesForChat.class);
    private final KnowledgeNoteSearch search;
    public KnowledgeNotesForChat(KnowledgeNoteSearch search) { this.search = search; }
    /** score is the hybrid retrieval (RRF) score of this note for the query; null when unknown. */
    public record Citation(String noteId, String title, String category, String excerpt, String reference, Double score) {
        public Citation(String noteId, String title, String category, String excerpt, String reference) {
            this(noteId, title, category, excerpt, reference, null);
        }
    }
    /** Stable across steps and saved messages; derived from a retrieved resource, never model output. */
    private static String reference(String id) {
        return "KB-" + java.util.UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 12);
    }
    public List<Citation> retrieve(String owner, String query) {
        if (query == null || query.isBlank()) return List.of();
        try {
            return search.search(owner, KnowledgeNoteSearch.bounded(query, 2000), 3).items().stream().limit(3)
                    .map(hit -> new Citation(KnowledgeNoteSearch.bounded(hit.id(), 100), KnowledgeNoteSearch.bounded(hit.title(), 120),
                            KnowledgeNoteSearch.bounded(hit.category(), 10), KnowledgeNoteSearch.bounded(hit.excerpt(), 800), reference(hit.id()), hit.score())).toList();
        } catch (RuntimeException ex) {
            log.warn("Private note context unavailable ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }
}
