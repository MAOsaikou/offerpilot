package com.offerpilot.conversation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageCardsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void selectKeepsValidCardsInOrderAndDropsTheRest() throws Exception {
        ObjectNode match = json.createObjectNode().put("score", 80);
        ObjectNode board = json.createObjectNode().put("change", "CREATED");
        var incoming = List.of(
                new MessageCard("card_job_1", "job_match", match),
                new MessageCard("card_job_1", "board_update", board),
                new MessageCard("bad id", "job_match", match),
                new MessageCard("card_x", "not_a_card", match),
                new MessageCard("card_board_1", "board_update", board),
                new MessageCard("card_evidence_1", "job_evidence_match", match));
        var kept = MessageCards.select("已生成", incoming, json);
        assertThat(kept).extracting(MessageCard::cardId).containsExactly("card_job_1", "card_board_1", "card_evidence_1");
        var parts = json.readTree(MessageCards.encode("已生成", incoming, json));
        assertThat(parts).hasSize(4);
        assertThat(parts.get(1).path("cardId").asText()).isEqualTo("card_job_1");
        assertThat(parts.get(1).path("cardType").asText()).isEqualTo("job_match");
        assertThat(parts.get(1).path("payload").path("score").asInt()).isEqualTo(80);
        var names = new ArrayList<String>();
        parts.get(1).fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("type", "cardId", "cardType", "payload");
    }

    @Test
    void payloadMustBeAnObjectWithinTheSizeAndCountCaps() {
        assertThat(MessageCards.parse("card_1", "reminder", Map.of("title", "提醒"), json)).isNotNull();
        assertThat(MessageCards.parse("card_1", "reminder", List.of("提醒"), json)).isNull();
        assertThat(MessageCards.parse("card_1", "reminder", null, json)).isNull();
        ObjectNode huge = json.createObjectNode().put("blob", "x".repeat(MessageCards.MAX_PAYLOAD_CHARS));
        assertThat(MessageCards.parse("card_big", "job_match", huge, json)).isNull();

        var cards = new ArrayList<MessageCard>();
        for (int i = 0; i < MessageCards.MAX_CARDS + 1; i++) {
            cards.add(new MessageCard("card_" + i, "reminder", json.createObjectNode().put("title", "t" + i)));
        }
        assertThat(MessageCards.select("正文", cards, json)).hasSize(MessageCards.MAX_CARDS);
    }

    @Test
    void oversizedPartsKeepTheTextAndSkipCardsThatDoNotFit() throws Exception {
        String text = "字".repeat(MessageCards.MAX_PARTS_CHARS);
        var card = new MessageCard("card_1", "reminder", json.createObjectNode().put("title", "提醒"));
        var parts = json.readTree(MessageCards.encode(text, List.of(card), json));
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).path("text").asText()).hasSize(MessageCards.MAX_PARTS_CHARS);
    }

    @Test
    void bufferClosesAfterSnapshot() {
        var buffer = new MessageCardBuffer(json);
        assertThat(buffer.accept("card_1", "job_match", Map.of("score", 80))).isTrue();
        assertThat(buffer.accept("card_1", "job_match", Map.of("score", 1))).isFalse();
        assertThat(buffer.accept("bad id", "job_match", Map.of())).isFalse();
        assertThat(buffer.snapshot()).extracting(MessageCard::cardId).containsExactly("card_1");
        assertThat(buffer.accept("card_2", "reminder", Map.of("title", "晚到"))).isFalse();
    }

    @Test
    void acceptsStarAndEvalCardsWithObjectPayloads() {
        var buffer = new MessageCardBuffer(json);
        assertThat(buffer.accept("cite1", "star_story_citations", Map.of("items", List.of(Map.of("id", "star_1", "title", "t")), "count", 1))).isTrue();
        assertThat(buffer.accept("cand1", "star_story_candidates", Map.of("items", List.of(), "count", 0))).isTrue();
        assertThat(buffer.accept("story1", "star_story", Map.of("id", "star_1", "title", "t"))).isTrue();
        assertThat(buffer.accept("eval1", "job_eval_report", Map.of("grade", "B", "overall", 80))).isTrue();
        assertThat(buffer.accept("bad", "star_story_citations", List.of())).isFalse();
    }
}
