package com.offerpilot.conversation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把本轮已发出的卡片收成助手消息 parts。
 * 上限与 docs/06 一致：不合规的卡片丢掉，正文始终保留。
 */
public final class MessageCards {
    public static final int MAX_CARDS = 8;
    public static final int MAX_PAYLOAD_CHARS = 32_768;
    public static final int MAX_PARTS_CHARS = 400_000;
    private static final Pattern CARD_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,79}");
    /** docs/05 §17 CardType，加上 §7 已落地的 job_evidence_match。 */
    private static final Set<String> CARD_TYPES = Set.of(
            "career_profile_confirm", "career_recommendation", "career_compare",
            "resume_diagnosis", "resume_diff",
            "job_card", "job_match", "job_evidence_match", "campus_calendar",
            "board_update",
            "interviewer_state", "interview_turn_score", "interview_report",
            "skill_gap", "skill_radar", "roadmap",
            "reminder");

    private MessageCards() {
    }

    public static boolean allowedType(String cardType) {
        return cardType != null && CARD_TYPES.contains(cardType);
    }

    /** 结构或大小不合法时返回 null，不抛给调用方。 */
    public static MessageCard parse(String cardId, String cardType, Object payload, ObjectMapper json) {
        if (cardId == null || !CARD_ID.matcher(cardId).matches() || !allowedType(cardType)) return null;
        JsonNode node = node(payload, json);
        if (node == null || !node.isObject()) return null;
        JsonNode copy = node.deepCopy();
        if (encoded(copy, json).length() > MAX_PAYLOAD_CHARS) return null;
        return new MessageCard(cardId, cardType, copy);
    }

    /**
     * 按发出顺序保留放得下的卡片。重复 cardId、非法卡片和使 parts 超过总上限的卡片被跳过。
     */
    public static List<MessageCard> select(String text, List<MessageCard> incoming, ObjectMapper json) {
        if (incoming == null || incoming.isEmpty()) return List.of();
        List<MessageCard> kept = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (MessageCard card : incoming) {
            if (card == null || kept.size() >= MAX_CARDS || !ids.add(card.cardId())) continue;
            MessageCard valid = parse(card.cardId(), card.cardType(), card.payload(), json);
            if (valid == null) {
                ids.remove(card.cardId());
                continue;
            }
            kept.add(valid);
            if (encodedLength(text, kept, json) > MAX_PARTS_CHARS) {
                kept.remove(kept.size() - 1);
                ids.remove(valid.cardId());
            }
        }
        return List.copyOf(kept);
    }

    public static String encode(String text, List<MessageCard> cards, ObjectMapper json) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", text == null ? "" : text));
        for (MessageCard card : select(text, cards, json)) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "card");
            part.put("cardId", card.cardId());
            part.put("cardType", card.cardType());
            part.put("payload", card.payload());
            parts.add(part);
        }
        return write(parts, json);
    }

    private static int encodedLength(String text, List<MessageCard> cards, ObjectMapper json) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", text == null ? "" : text));
        for (MessageCard card : cards) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "card");
            part.put("cardId", card.cardId());
            part.put("cardType", card.cardType());
            part.put("payload", card.payload());
            parts.add(part);
        }
        return write(parts, json).length();
    }

    private static JsonNode node(Object payload, ObjectMapper json) {
        if (payload instanceof JsonNode existing) return existing;
        try {
            return json.valueToTree(payload);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String encoded(JsonNode node, ObjectMapper json) {
        return write(node, json);
    }

    private static String write(Object value, ObjectMapper json) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
