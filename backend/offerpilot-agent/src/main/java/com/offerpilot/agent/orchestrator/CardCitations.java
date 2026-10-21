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
                for(var item:data.path("items")) add(sources,index,item.path("noteId").asText(),item.path("title").asText(),item.path("excerpt").asText(),null,"notes");
            }
            if ("career_recommendation".equals(card.cardType())) {
                for(var recommendation:data.path("recommendations")) for(var item:recommendation.path("citations"))
                    add(sources,index,item.path("occupationId").asText(),item.path("title").asText(),item.path("excerpt").asText(),item.path("url").asText(null),"occupations");
            }
        }
        return List.copyOf(sources);
    }
    private static void add(List<AgentEvent.CitationSource> sources,AtomicInteger index,String id,String title,String snippet,String url,String collection) {
        if(id.isBlank() || title.isBlank() || snippet.isBlank() || sources.size()>=30) return;
        if(sources.stream().anyMatch(s -> s.docId().equals(id) && s.snippet().equals(snippet))) return;
        sources.add(new AgentEvent.CitationSource(id,title,snippet,null,url,collection,index.incrementAndGet()));
    }
}
