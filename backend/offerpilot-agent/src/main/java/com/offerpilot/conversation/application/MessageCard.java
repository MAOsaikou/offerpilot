package com.offerpilot.conversation.application;

import com.fasterxml.jackson.databind.JsonNode;

/** 助手消息 parts 中的一张卡片。payload 已规范成 JSON 对象。 */
public record MessageCard(String cardId, String cardType, JsonNode payload) {
}
