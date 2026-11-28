package com.offerpilot.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.resume.application.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small read-only application boundary. Profiles remain authoritative; notes are user-authored evidence. */
@Service
public class KnowledgeNotesForChat {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeNotesForChat.class);
    private final KnowledgeNoteSearch search;
    private ResumeService resumes;
    public KnowledgeNotesForChat(KnowledgeNoteSearch search) { this.search = search; }
    @Autowired(required = false)
    public void setResumes(ResumeService resumes) { this.resumes = resumes; }
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
            var notes = search.search(owner, KnowledgeNoteSearch.bounded(query, 2000), 3).items().stream().limit(3)
                    .map(hit -> new Citation(KnowledgeNoteSearch.bounded(hit.id(), 100), KnowledgeNoteSearch.bounded(hit.title(), 120),
                            KnowledgeNoteSearch.bounded(hit.category(), 10), KnowledgeNoteSearch.bounded(hit.excerpt(), 800), reference(hit.id()), hit.score())).toList();
            var current = currentResume(owner, query);
            return current == null ? notes : prefer(notes, current.citation(), current.anchors());
        } catch (RuntimeException ex) {
            log.warn("Private note context unavailable ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }
    private record ResumeHit(Citation citation, List<String> anchors) {}
    /** Latest ready resume, only when the query shares a real lexical token with it. Score stays unknown. */
    private ResumeHit currentResume(String owner, String query) {
        if (resumes == null) return null;
        try {
            var resume = resumes.latestReady(owner);
            if (resume == null || resume.currentVersion() == null) return null;
            var version = resume.currentVersion();
            JsonNode structured = version.structured();
            String name = structured.path("basics").path("name").asText("").strip();
            var anchors = new ArrayList<String>();
            if (name.codePointCount(0, name.length()) >= 2) anchors.add(name);
            structured.path("projects").forEach(node -> {
                String project = node.path("name").asText("").strip();
                if (project.codePointCount(0, project.length()) >= 2) anchors.add(project);
            });
            String plain = version.plainText() == null ? "" : version.plainText();
            String document = plain + "\n" + name + "\n" + String.join("\n", anchors);
            if (!sharesToken(query, document)) return null;
            String excerptSource = anchors.isEmpty() ? plain : String.join("\n", anchors) + "\n" + plain;
            if (excerptSource.isBlank()) return null;
            String excerpt = KnowledgeNoteSearch.excerpt(excerptSource, query, 800);
            if (excerpt.isBlank()) excerpt = KnowledgeNoteSearch.bounded(excerptSource.strip(), 800);
            String title = name.isBlank() ? "当前简历" : "当前简历 · " + KnowledgeNoteSearch.bounded(name, 40);
            return new ResumeHit(new Citation(KnowledgeNoteSearch.bounded(resume.id(), 100),
                    KnowledgeNoteSearch.bounded(title, 120), "CURRENT_RESUME",
                    KnowledgeNoteSearch.bounded(excerpt, 800), reference(resume.id()), null), List.copyOf(anchors));
        } catch (RuntimeException ex) {
            log.warn("Current resume citation unavailable ({})", ex.getClass().getSimpleName());
            return null;
        }
    }
    static boolean sharesToken(String query, String document) {
        if (query == null || document == null || document.isBlank()) return false;
        var tokens = new java.util.HashSet<>(OccupationDocument.lexicalTokens(query));
        if (tokens.isEmpty()) return false;
        tokens.retainAll(new java.util.HashSet<>(OccupationDocument.lexicalTokens(document)));
        return !tokens.isEmpty();
    }
    /** Drop notes that name a different project or person once the current resume itself matches the query. */
    static List<Citation> prefer(List<Citation> notes, Citation current, List<String> anchors) {
        var kept = new ArrayList<Citation>();
        kept.add(current);
        boolean anchored = anchors.stream().anyMatch(anchor -> anchor != null && anchor.codePointCount(0, anchor.length()) >= 2);
        for (var note : notes) {
            if (kept.size() == 3) break;
            if (anchored && !mentions(note, anchors)) continue;
            kept.add(note);
        }
        return List.copyOf(kept);
    }
    private static boolean mentions(Citation note, List<String> anchors) {
        String haystack = (note.title() + "\n" + note.excerpt()).toLowerCase(Locale.ROOT);
        for (String anchor : anchors) {
            if (anchor == null) continue;
            String needle = anchor.strip().toLowerCase(Locale.ROOT);
            if (needle.codePointCount(0, needle.length()) >= 2 && haystack.contains(needle)) return true;
        }
        return false;
    }
}
