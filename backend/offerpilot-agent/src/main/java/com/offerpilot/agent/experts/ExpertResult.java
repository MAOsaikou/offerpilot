package com.offerpilot.agent.experts;

import java.util.List;

/** Cards exist only after successful business reads/writes. */
public final class ExpertResult {
    public record Card(String cardId, String cardType, Object payload) {}
    private final String summary;
    private final List<Card> cards;
    public ExpertResult(String summary, Card card) { this(card == null ? List.of() : List.of(card), summary); }
    private ExpertResult(List<Card> cards, String summary) { this.summary = summary; this.cards = List.copyOf(cards); }
    public String summary() { return summary; }
    /** Compatibility accessor for existing single-card callers. */
    public Card card() { return cards.isEmpty() ? null : cards.get(0); }
    public List<Card> cards() { return cards; }
    public static ExpertResult text(String summary) { return cards(summary, List.of()); }
    public static ExpertResult card(String summary, String id, String type, Object payload) { return cards(summary, new Card(id, type, payload)); }
    public static ExpertResult cards(String summary, Card... cards) { return cards(summary, List.of(cards)); }
    public static ExpertResult cards(String summary, List<Card> cards) { return new ExpertResult(cards, summary); }
}
