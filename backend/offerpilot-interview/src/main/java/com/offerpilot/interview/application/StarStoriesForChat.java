package com.offerpilot.interview.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StarStoriesForChat {
    private static final Logger log = LoggerFactory.getLogger(StarStoriesForChat.class);
    private final StarStoryService stories;

    public StarStoriesForChat(StarStoryService stories) { this.stories = stories; }

    public List<StarStoryService.Citation> retrieveForSession(String owner, String sessionId, String query) {
        // Fail explicitly if session lookup/search fails; do not silently claim there is no evidence.
        return stories.recallForSession(owner, sessionId, query, 3);
    }

    public List<StarStoryService.Citation> retrieve(String owner, String query) {
        try {
            return stories.recall(owner, query == null ? "" : query, 3);
        } catch (Exception ex) {
            log.warn("star story recall failed for owner={}", owner, ex);
            return List.of();
        }
    }
}
