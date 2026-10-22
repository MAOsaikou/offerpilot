package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.experts.ExpertResult;
import java.util.*;

/** Read-only views of real tool payloads. Missing dates/scores remain missing. */
final class ToolCardProjections {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static String id(ExpertResult.Card card,String suffix) { return UUID.nameUUIDFromBytes((card.cardId()+":"+suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
    static ExpertResult expand(ExpertResult result) {
        var cards = new ArrayList<>(result.cards());
        for (var card : result.cards()) {
            var data = JSON.valueToTree(card.payload());
            if ("skill_gap".equals(card.cardType())) {
                var radar = JSON.createObjectNode(); var axes = radar.putArray("axes");
                for (var skill : data.path("skills")) axes.addObject().put("name",skill.path("name").asText())
                        .set("current",skill.path("current"));
                for (int i=0;i<axes.size();i++) ((com.fasterxml.jackson.databind.node.ObjectNode)axes.get(i)).set("required",data.path("skills").get(i).path("required"));
                cards.add(new ExpertResult.Card(id(card,"radar"),"skill_radar",radar));
            }
            if ("job_card".equals(card.cardType())) {
                var calendar = JSON.createObjectNode(); var items = calendar.putArray("items");
                var jobs = data.path("items").isArray() ? data.path("items") : JSON.createArrayNode().add(data);
                for (var job : jobs) {
                    String deadline = job.path("deadline").asText("");
                    try { java.time.LocalDate.parse(deadline.length() >= 10 ? deadline.substring(0,10) : deadline); }
                    catch (RuntimeException invalid) { continue; }
                    var item=items.addObject().put("company",job.path("company").asText()).put("batch","")
                            .put("deadline",deadline).put("url",job.path("url").asText());
                    item.putArray("positions").add(job.path("title").asText());
                }
                cards.add(new ExpertResult.Card(id(card,"calendar"),"campus_calendar",calendar));
            }
            if ("interview_report".equals(card.cardType())) {
                int index=0;
                for (var turn : data.path("turns")) {
                    if (index < data.path("turns").size()-1) { index++; continue; }
                    var score=JSON.createObjectNode().put("turnIndex",index).put("brief",turn.path("feedback").asText());
                    score.set("score",turn.path("score")); score.set("star",turn.path("star"));
                    cards.add(new ExpertResult.Card(id(card,"turn-"+index++),"interview_turn_score",score));
                }
            }
        }
        return ExpertResult.cards(result.summary(),cards);
    }
}
