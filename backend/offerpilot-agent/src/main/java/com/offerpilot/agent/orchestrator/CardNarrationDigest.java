package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.agent.experts.ExpertResult;
import java.util.List;
import java.util.stream.StreamSupport;

/** Small allowlisted view of cards for narration; transport previews remain independently bounded. */
final class CardNarrationDigest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CardNarrationDigest() {}
    static String of(List<ExpertResult.Card> cards) {
        var digest = JSON.createArrayNode();
        for (var card : cards) {
            JsonNode payload = JSON.valueToTree(card.payload());
            var row = JSON.createObjectNode().put("cardType", card.cardType());
            switch (card.cardType()) {
                case "skill_gap" -> {
                    row.set("target", fields(payload.path("target"), "title"));
                    var skills = row.putArray("topGaps");
                    row.set("summary", payload.path("summary"));
                    StreamSupport.stream(payload.path("skills").spliterator(), false).limit(8).forEach(n -> {
                        var view = fields(n, "id", "name", "required", "current", "gap", "learnFirst");
                        view.set("evidenceSources", evidence(n.path("evidenceSources")));
                        skills.add(view);
                    });
                }
                case "roadmap" -> {
                    var weeks = row.putArray("weeks");
                    for (var week : payload.path("weeks")) {
                        if (weeks.size() == 4) break;
                        var view = fields(week, "index", "theme");
                        view.set("gapIds", first(week.path("gapIds"), 8));
                        view.set("evidenceSources", evidence(week.path("evidenceSources")));
                        var titles = view.putArray("taskTitles");
                        for (var item : week.path("items")) {
                            if (titles.size() == 3) break;
                            titles.add(shortText(item.path("title").asText()));
                        }
                        weeks.add(view);
                    }
                }
                case "interview_report" -> {
                    row.set("overall", payload.path("overall"));
                    row.set("dimensions", fields(payload.path("dimensions"), "professional", "expression", "logic", "jobFit"));
                    row.set("weaknesses", first(payload.path("weaknesses"), 3));
                    row.set("advice", first(payload.path("advice"), 3));
                    var turns = row.putArray("turns");
                    for (var turn : payload.path("turns")) {
                        if (turns.size() == 2) break;
                        var view = fields(turn, "question", "score", "feedback");
                        view.set("star", fields(turn.path("star"), "situation", "task", "action", "result"));
                        turns.add(view);
                    }
                }
                case "interviewer_state", "interview_session" -> row.set("turn", fields(payload, "state", "questionIndex", "total", "question", "note"));
                case "star_story_citations" -> {
                    row.set("count", payload.path("count"));
                    var items = row.putArray("items");
                    for (var item : payload.path("items")) {
                        if (items.size() == 3) break;
                        items.add(fields(item, "id", "title", "role", "starCount", "excerpt"));
                    }
                }
                case "career_recommendation" -> {
                    row.set("hollandCode", payload.path("hollandCode"));
                    var recommendations = row.putArray("recommendations");
                    for (var recommendation : payload.path("recommendations")) {
                        if (recommendations.size() == 5) break;
                        var view = fields(recommendation, "name", "family", "hollandCode", "similarity");
                        view.set("reasons", first(recommendation.path("reasons"), 3));
                        recommendations.add(view);
                    }
                }
                default -> { continue; }
            }
            digest.add(row);
        }
        return digest.toString();
    }
    private static JsonNode evidence(JsonNode sources) {
        var output = JSON.createArrayNode();
        for (var source : sources) {
            if (output.size() == 12) break;
            output.add(fields(source, "type", "id", "label", "excerpt"));
        }
        return output;
    }
    private static ObjectNode fields(JsonNode source, String... names) {
        var output = JSON.createObjectNode();
        for (String name : names) {
            var value = source.path(name);
            if (value.isTextual()) output.put(name, shortText(value.asText()));
            else if (value.isNumber() || value.isBoolean()) output.set(name, value);
        }
        return output;
    }
    private static JsonNode first(JsonNode values, int limit) {
        var output = JSON.createArrayNode();
        for (var value : values) {
            if (output.size() == limit) break;
            if (value.isTextual()) output.add(shortText(value.asText()));
        }
        return output;
    }
    private static String shortText(String text) { return text.length() > 300 ? text.substring(0, 300) + "…" : text; }
}
