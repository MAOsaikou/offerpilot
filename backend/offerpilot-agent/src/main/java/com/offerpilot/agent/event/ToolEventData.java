package com.offerpilot.agent.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.offerpilot.llm.privacy.PiiMasker;
import com.offerpilot.llm.safety.OutputModeration;

import java.util.List;
import java.util.regex.Pattern;

/** Display-only copies. Approval/execution always use the original, owner-scoped arguments. */
public final class ToolEventData {
    public static final int MAX_CHARS = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?i).*(?:password|secret|token|authorization|cookie|api[_-]?key|credential|private[_-]?key).*");
    private static final Pattern INLINE_SECRET = Pattern.compile("(?i)(?:password|api[_-]?key|secret|authorization)[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s,;\\\"'}]{4,}");
    private static final OutputModeration MODERATION = new OutputModeration(
            System.getenv().entrySet().stream().filter(entry -> PRIVATE_KEY.matcher(entry.getKey()).matches())
                    .map(java.util.Map.Entry::getValue).toList());
    private ToolEventData() {}

    public static String text(String value) {
        if (value == null) return "";
        if (MODERATION.violation(value) != null || INLINE_SECRET.matcher(value).find() || value.matches("(?is).*Bearer\\s+[A-Za-z0-9_.~+/-]{8,}.*")) return "[REDACTED]";
        return new PiiMasker.Session(List.of(value)).mask(value);
    }

    public static Object safe(Object value) {
        JsonNode node = scrub(JSON.valueToTree(value));
        String encoded = node.toString();
        if (encoded.length() <= MAX_CHARS) return JSON.convertValue(node, Object.class);
        // Keep a valid JSON envelope, with explicit truncation instead of a cut-off JSON document.
        return java.util.Map.of("truncated", true, "preview", com.offerpilot.common.text.TextBounds.truncate(encoded, 2000));
    }

    private static JsonNode scrub(JsonNode node) {
        if (node == null || node.isNull()) return JsonNodeFactory.instance.nullNode();
        if (node.isTextual()) return JsonNodeFactory.instance.textNode(text(node.asText()));
        if (node.isObject()) {
            var out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> out.set(text(entry.getKey()), PRIVATE_KEY.matcher(entry.getKey()).matches()
                    ? JsonNodeFactory.instance.textNode("[REDACTED]") : scrub(entry.getValue())));
            return out;
        }
        if (node.isArray()) {
            var out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(scrub(item)));
            return out;
        }
        return node;
    }
}
