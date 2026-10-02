package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import java.util.*;
import java.util.regex.Pattern;

/** Independent string replacements keep every subset anchored to the original version. */
public final class ResumePatches {
    private ResumePatches() {}
    private static final String INDEX = "(?:0|[1-9][0-9]{0,8})";
    private static final Pattern PATH = Pattern.compile("(?:/summary|/(?:experience|projects)/" + INDEX
        + "/bullets/" + INDEX + "|/skills/" + INDEX + "/items/" + INDEX + ")");
    private static final Set<String> PATCH_FIELDS = Set.of("id", "op", "path", "from", "value", "reason",
        "jdEvidence", "needsUserConfirmation", "confirmationHint");

    public static JsonNode validateBatch(JsonNode source, JsonNode patches) {
        ResumeStructure.validate(source);
        require(patches != null && patches.isArray() && patches.size() > 0 && patches.size() <= 50);
        Set<String> ids = new HashSet<>(), paths = new HashSet<>();
        for (JsonNode patch : patches) {
            fields(patch, PATCH_FIELDS);
            require(text(patch.get("id"), 80, false) && patch.get("id").asText().matches("[A-Za-z0-9_-]{1,80}"));
            require(ids.add(patch.get("id").asText()));
            require("replace".equals(patch.path("op").asText()));
            require(text(patch.get("path"), 150, false) && PATH.matcher(patch.get("path").asText()).matches());
            String path = patch.get("path").asText();
            require(paths.add(path));
            require(text(patch.get("from"), 5000, true) && text(patch.get("value"), 5000, true));
            require(source.at(path).isTextual() && source.at(path).equals(patch.get("from")));
            require(!patch.get("from").equals(patch.get("value")));
            require(text(patch.get("reason"), 2000, false));
            require(patch.has("needsUserConfirmation") && patch.get("needsUserConfirmation").isBoolean());
            optionalText(patch, "jdEvidence");
            optionalText(patch, "confirmationHint");
            if (patch.get("needsUserConfirmation").booleanValue()) require(text(patch.get("confirmationHint"), 2000, false));
        }
        return patches.deepCopy();
    }

    /** Canonical decisions make retries independent of checkbox order and JSON property order. */
    public static ObjectNode decision(JsonNode body) {
        fields(body, Set.of("batchId", "accepted", "edited", "confirmed"));
        require(text(body.get("batchId"), 100, false));
        var accepted = ids(body.get("accepted"));
        var confirmed = body.has("confirmed") ? ids(body.get("confirmed")) : new TreeSet<String>();
        var factory = JsonNodeFactory.instance;
        ObjectNode edited = factory.objectNode();
        if (body.has("edited")) {
            JsonNode edits = body.get("edited");
            require(edits.isObject() && edits.size() <= 50);
            var keys = new TreeSet<String>();
            edits.fieldNames().forEachRemaining(keys::add);
            for (String id : keys) {
                require(id.matches("[A-Za-z0-9_-]{1,80}") && !accepted.contains(id));
                fields(edits.get(id), Set.of("value"));
                require(text(edits.get(id).get("value"), 5000, true));
                edited.set(id, edits.get(id).get("value"));
            }
        }
        Set<String> selected = new HashSet<>(accepted);
        edited.fieldNames().forEachRemaining(selected::add);
        require(!selected.isEmpty() && selected.size() <= 50 && selected.containsAll(confirmed));
        ObjectNode result = factory.objectNode();
        result.put("batchId", body.get("batchId").asText());
        var accepts = result.putArray("accepted"); accepted.forEach(accepts::add);
        result.set("edited", edited);
        var confirms = result.putArray("confirmed"); confirmed.forEach(confirms::add);
        return result;
    }

    public static JsonNode apply(JsonNode source, JsonNode patches, ObjectNode decision) {
        validateBatch(source, patches);
        Set<String> selected = new HashSet<>(), confirmed = new HashSet<>(), known = new HashSet<>();
        decision.path("accepted").forEach(id -> selected.add(id.asText()));
        decision.path("edited").fieldNames().forEachRemaining(selected::add);
        decision.path("confirmed").forEach(id -> confirmed.add(id.asText()));
        ObjectNode result = source.deepCopy();
        for (JsonNode patch : patches) {
            String id = patch.get("id").asText(); known.add(id);
            if (!selected.contains(id)) continue;
            require(!patch.get("needsUserConfirmation").booleanValue() || confirmed.contains(id));
            String path = patch.get("path").asText();
            JsonNode value = decision.path("edited").has(id) ? decision.path("edited").get(id) : patch.get("value");
            require(!value.equals(patch.get("from")));
            int slash = path.lastIndexOf('/');
            JsonNode parent = result.at(path.substring(0, slash));
            String key = path.substring(slash + 1);
            if (parent.isArray()) ((com.fasterxml.jackson.databind.node.ArrayNode) parent).set(Integer.parseInt(key), value.deepCopy());
            else ((ObjectNode) parent).set(key, value.deepCopy());
        }
        require(known.containsAll(selected));
        return ResumeStructure.validate(result);
    }

    private static SortedSet<String> ids(JsonNode node) {
        require(node != null && node.isArray() && node.size() <= 50);
        SortedSet<String> result = new TreeSet<>();
        for (JsonNode id : node) {
            require(text(id, 80, false) && id.asText().matches("[A-Za-z0-9_-]{1,80}") && result.add(id.asText()));
        }
        return result;
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject());
        node.fieldNames().forEachRemaining(key -> require(allowed.contains(key)));
    }
    private static boolean text(JsonNode node, int max, boolean emptyAllowed) {
        return node != null && node.isTextual() && node.textValue().length() <= max && (emptyAllowed || !node.textValue().isBlank());
    }
    private static void optionalText(JsonNode node, String field) {
        if (node.has(field)) require(text(node.get(field), 2000, true));
    }
    private static void require(boolean valid) {
        if (!valid) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "补丁或采纳决策无效，请核对原文、选中项与确认项");
    }
}
