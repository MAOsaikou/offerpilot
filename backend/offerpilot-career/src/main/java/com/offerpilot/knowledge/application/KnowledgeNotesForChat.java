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
    public record Citation(String noteId, String title, String category, String excerpt) {}
    public List<Citation> retrieve(String owner, String query) {
        if (query == null || query.isBlank()) return List.of();
        try {
            return search.search(owner, KnowledgeNoteSearch.bounded(query, 2000), 3).items().stream().limit(3)
                    .map(hit -> new Citation(KnowledgeNoteSearch.bounded(hit.id(), 100), KnowledgeNoteSearch.bounded(hit.title(), 120),
                            KnowledgeNoteSearch.bounded(hit.category(), 10), KnowledgeNoteSearch.bounded(hit.excerpt(), 800))).toList();
        } catch (RuntimeException ex) {
            log.warn("Private note context unavailable ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }
}
