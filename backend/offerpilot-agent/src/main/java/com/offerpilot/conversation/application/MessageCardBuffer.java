package com.offerpilot.conversation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** 本轮 SSE 中已接受的卡片。运行结束前关闭，避免取消之后再收进持久化快照。 */
public final class MessageCardBuffer {
    private static final Logger log = LoggerFactory.getLogger(MessageCardBuffer.class);
    private final ObjectMapper json;
    private final List<MessageCard> cards = new ArrayList<>();
    private boolean closed;

    public MessageCardBuffer(ObjectMapper json) {
        this.json = json;
    }

    public synchronized boolean accept(String cardId, String cardType, Object payload) {
        if (closed) return false;
        MessageCard card = MessageCards.parse(cardId, cardType, payload, json);
        if (card == null) {
            log.warn("丢弃不合规卡片 cardId={} cardType={}", cardId, cardType);
            return false;
        }
        if (cards.stream().anyMatch(existing -> existing.cardId().equals(card.cardId()))) {
            log.warn("丢弃重复卡片 cardId={}", cardId);
            return false;
        }
        if (cards.size() >= MessageCards.MAX_CARDS) {
            log.warn("丢弃超出张数上限的卡片 cardId={}", cardId);
            return false;
        }
        cards.add(card);
        return true;
    }

    public synchronized List<MessageCard> snapshot() {
        closed = true;
        return List.copyOf(cards);
    }
}
