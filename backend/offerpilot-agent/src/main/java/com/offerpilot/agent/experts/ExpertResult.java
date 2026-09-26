package com.offerpilot.agent.experts;

/** 一步专家的文字结果。card 仅在真实业务调用成功时存在。 */
public record ExpertResult(String summary, Card card) {
    public record Card(String cardId, String cardType, Object payload) {
    }

    public static ExpertResult text(String summary) {
        return new ExpertResult(summary, null);
    }

    public static ExpertResult card(String summary, String cardId, String cardType, Object payload) {
        return new ExpertResult(summary, new Card(cardId, cardType, payload));
    }
}
