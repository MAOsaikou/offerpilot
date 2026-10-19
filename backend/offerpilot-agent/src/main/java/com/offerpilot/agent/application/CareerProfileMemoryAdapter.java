package com.offerpilot.agent.application;

import com.offerpilot.career.application.CareerProfileMemory;
import com.offerpilot.memory.application.UserMemoryService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Fixed prefixes are shared with manually authored facts in Settings; arbitrary prose is not extracted. */
@Component
public class CareerProfileMemoryAdapter implements CareerProfileMemory {
    public static final String TARGET = "目标职业：";
    public static final String CITY = "意向城市：";
    public static final String MAJOR = "专业：";
    public static final String HOLLAND = "兴趣码：";
    private final UserMemoryService memories;
    public CareerProfileMemoryAdapter(UserMemoryService memories) { this.memories = memories; }

    @Override public Seed read(String owner) {
        if (!memories.enabled(owner)) return new Seed("", "", "");
        var facts = memories.list(owner).items();
        return new Seed(value(facts, TARGET), value(facts, CITY), value(facts, MAJOR));
    }

    @Override @Transactional
    public void sync(String owner, String target, String city, String major, String hollandCode) {
        if (!memories.enabled(owner)) return;
        // Remove blank prefixes first so replacing facts at the cap does not fail unnecessarily.
        var fields = List.of(new Field(UserMemoryService.Kind.GOAL, TARGET, target),
                new Field(UserMemoryService.Kind.LOCATION, CITY, city),
                new Field(UserMemoryService.Kind.OTHER, MAJOR, major),
                new Field(UserMemoryService.Kind.PREFERENCE, HOLLAND, hollandCode));
        fields.stream().sorted(java.util.Comparator.comparing(f -> !f.value().isBlank()))
                .forEach(f -> memories.upsertPrefixed(owner, f.kind(), f.prefix(), f.value()));
    }

    @Override @Transactional
    public void syncAssessment(String owner, String city, String hollandCode) {
        if (!memories.enabled(owner)) return;
        if (!city.isBlank()) memories.upsertPrefixed(owner, UserMemoryService.Kind.LOCATION, CITY, city);
        memories.upsertPrefixed(owner, UserMemoryService.Kind.PREFERENCE, HOLLAND, hollandCode);
    }

    private record Field(UserMemoryService.Kind kind, String prefix, String value) {}
    private static String value(List<UserMemoryService.Memory> facts, String prefix) {
        // Inventory is newest first. Ignore oversize/invalid manually authored values instead of corrupting the profile.
        return facts.stream().map(UserMemoryService.Memory::fact).filter(f -> f.startsWith(prefix))
                .map(f -> f.substring(prefix.length()).strip())
                .filter(v -> !v.isBlank() && v.codePointCount(0, v.length()) <= 100)
                .findFirst().orElse("");
    }
}
