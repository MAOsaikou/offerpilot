package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertResult;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class CardCitations {
    private static final ObjectMapper JSON = new ObjectMapper();
    static String describe(List<AgentEvent.CitationSource> sources) {
        return JSON.valueToTree(sources.stream().map(source -> Map.of("index",source.index(),"title",source.title(),"snippet",source.snippet())).toList()).toString();
    }
    static List<AgentEvent.CitationSource> read(List<ExpertResult.Card> cards, AtomicInteger index) {
        var sources = new ArrayList<AgentEvent.CitationSource>();
        for (var card: cards) {
            JsonNode data=JSON.valueToTree(card.payload());
            if ("knowledge_note_citations".equals(card.cardType())) {
                for(var item:data.path("items")) add(sources,index,item.path("noteId").asText(),item.path("title").asText(),item.path("excerpt").asText(),score(item.path("score")),null,"notes");
            }
            if ("career_recommendation".equals(card.cardType())) {
                for(var recommendation:data.path("recommendations")) {
                    // Occupation sources carry the recommendation's interest similarity, the same value the card displays.
                    Double similarity=score(recommendation.path("similarity"));
                    for(var item:recommendation.path("citations"))
                        add(sources,index,item.path("occupationId").asText(),item.path("title").asText(),item.path("excerpt").asText(),similarity,item.path("url").asText(null),"occupations");
                }
            }
        }
        return List.copyOf(sources);
    }
    private static Double score(JsonNode node) {
        return node.isNumber() && Double.isFinite(node.asDouble()) ? node.asDouble() : null;
    }
    private static void add(List<AgentEvent.CitationSource> sources,AtomicInteger index,String id,String title,String snippet,Double score,String url,String collection) {
        if(id.isBlank() || title.isBlank() || snippet.isBlank() || sources.size()>=30) return;
        if(sources.stream().anyMatch(s -> s.docId().equals(id) && s.snippet().equals(snippet))) return;
        sources.add(new AgentEvent.CitationSource(id,title,snippet,score,url,collection,index.incrementAndGet()));
    }
}
