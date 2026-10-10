package com.offerpilot.llm.privacy;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Deterministic recognizers and a request-local reversible map. No content is logged or persisted. */
public final class PiiMasker {
    private PiiMasker() {}
    private static final Pattern ENTITIES = Pattern.compile(
            "(?<EMAIL>(?<![A-Za-z0-9_.+%-])[A-Za-z0-9_+%-]+(?:\\.[A-Za-z0-9_+%-]+)*@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,63}(?![A-Za-z0-9_-]))"
            + "|(?<ID>(?<![A-Za-z0-9_.])\\d{17}[0-9Xx](?![A-Za-z0-9_]))"
            + "|(?<PHONE>(?<![A-Za-z0-9_.])(?:\\+86[ -]?)?1[3-9]\\d{9}(?![A-Za-z0-9_.]))");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(?:PHONE|EMAIL|ID_CARD)_\\d+]");
    private static final int[] WEIGHTS = {7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};
    private static boolean validId(String value) {
        if (value.charAt(0) == '0' || value.substring(14, 17).equals("000")) return false;
        try {
            LocalDate date = LocalDate.parse(value.substring(6,14), DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
            if (date.getYear() < 1800 || date.getYear() > 2099) return false;
        } catch (java.time.DateTimeException ex) { return false; }
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (value.charAt(i) - '0') * WEIGHTS[i];
        return "10X98765432".charAt(sum % 11) == Character.toUpperCase(value.charAt(17));
    }
    public static boolean detects(String text) { return !new Session(List.of(text == null ? "" : text)).mask(text).equals(text == null ? "" : text); }

    public static final class Session {
        private final Map<String, String> originals = new LinkedHashMap<>();
        private final Map<String, String> aliases = new HashMap<>();
        private final Set<String> reserved = new HashSet<>();
        private final Map<String, Integer> counts = new HashMap<>();
        public Session(List<String> texts) {
            for (String text : texts) if (text != null) PLACEHOLDER.matcher(text).results().forEach(m -> reserved.add(m.group()));
        }
        public String mask(String text) {
            if (text == null) return "";
            var matcher = ENTITIES.matcher(text);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                String type = matcher.group("EMAIL") != null ? "EMAIL" : matcher.group("ID") != null ? "ID_CARD" : "PHONE";
                String value = matcher.group();
                if (type.equals("ID_CARD") && !validId(value)) continue;
                // Explicit business identifiers remain identifiers, even if an 11-digit value resembles a phone.
                String before = text.substring(Math.max(0, matcher.start() - 40), matcher.start());
                if (!type.equals("EMAIL") && before.matches("(?is).*(?:jobId|orderId|岗位编号|订单号)\\s*[=:：]\\s*\"?$")) continue;
                String key = type + ":" + value;
                String alias = aliases.computeIfAbsent(key, ignored -> {
                    String candidate;
                    do { candidate = "[" + type + "_" + counts.merge(type, 1, Integer::sum) + "]"; } while (reserved.contains(candidate));
                    originals.put(candidate, value);
                    return candidate;
                });
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(alias));
            }
            matcher.appendTail(result);
            return result.toString();
        }
        public String restore(String text) {
            if (text == null) return null;
            // One pass only: restoring a value cannot recursively resolve user-authored placeholders.
            var matcher = PLACEHOLDER.matcher(text);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(originals.getOrDefault(matcher.group(), matcher.group())));
            matcher.appendTail(result);
            return result.toString();
        }
        public Prompt mask(Prompt prompt) {
            var messages = prompt.getInstructions().stream().map(this::maskMessage).toList();
            return new Prompt(messages, prompt.getOptions());
        }
        private Message maskMessage(Message message) {
            if (message instanceof UserMessage user) return user.mutate().text(mask(user.getText())).build();
            if (message instanceof SystemMessage system) return new SystemMessage(mask(system.getText()));
            if (message instanceof AssistantMessage assistant) return new AssistantMessage(mask(assistant.getText()), assistant.getMetadata(),
                    assistant.getToolCalls().stream().map(c -> new AssistantMessage.ToolCall(c.id(), c.type(), c.name(), mask(c.arguments()))).toList(), assistant.getMedia());
            if (message instanceof ToolResponseMessage tool) return new ToolResponseMessage(tool.getResponses().stream()
                    .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), mask(r.responseData()))).toList(), tool.getMetadata());
            throw new IllegalArgumentException("Unsupported model message type");
        }
        public ChatResponse restore(ChatResponse response) { return transform(response, (index, text) -> restore(text)); }
        private ChatResponse transform(ChatResponse response, java.util.function.BiFunction<Integer, String, String> text) {
            List<Generation> results = new ArrayList<>();
            for (int i = 0; i < response.getResults().size(); i++) {
                var generation = response.getResults().get(i);
                var output = generation.getOutput();
                results.add(new Generation(new AssistantMessage(text.apply(i, output.getText()), output.getMetadata(),
                        output.getToolCalls(), output.getMedia()), generation.getMetadata()));
            }
            return new ChatResponse(results, response.getMetadata());
        }
        public Flux<ChatResponse> restore(Flux<ChatResponse> source) {
            return Flux.defer(() -> {
                Map<Integer, String> pending = new HashMap<>();
                ChatResponse[] last = {null};
                return source.map(response -> {
                    if (!response.getResults().isEmpty()) last[0] = response;
                    return transform(response, (index, text) -> {
                        String combined = pending.getOrDefault(index, "") + (text == null ? "" : text);
                        int keep = 0;
                        for (String alias : originals.keySet()) for (int size = 1; size < alias.length() && size <= combined.length(); size++)
                            if (combined.endsWith(alias.substring(0, size))) keep = Math.max(keep, size);
                        pending.put(index, combined.substring(combined.length() - keep));
                        return restore(combined.substring(0, combined.length() - keep));
                    });
                }).concatWith(Flux.defer(() -> {
                    if (last[0] == null || pending.values().stream().allMatch(String::isEmpty)) return Flux.empty();
                    // Flush an unfinished token only on normal completion, never on cancellation/error.
                    return Flux.just(transform(last[0], (index, text) -> restore(pending.getOrDefault(index, ""))));
                }));
            });
        }
    }
    public static Session forPrompt(Prompt prompt) {
        List<String> texts = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            texts.add(message.getText());
            if (message instanceof ToolResponseMessage tool) tool.getResponses().forEach(r -> texts.add(r.responseData()));
            if (message instanceof AssistantMessage assistant) assistant.getToolCalls().forEach(c -> texts.add(c.arguments()));
        }
        return new Session(texts);
    }
}
