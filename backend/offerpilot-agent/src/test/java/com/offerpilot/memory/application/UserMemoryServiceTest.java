package com.offerpilot.memory.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.List;
import static com.offerpilot.memory.application.UserMemoryService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMemoryServiceTest {
    @Test void prefixedFactsUpdateStableIdsWithoutSpammingAndBlankRemovesOnlyMatchingFacts() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM users"), eq(String.class), eq("alice"))).thenReturn(List.of("alice"));
        var memories = spy(new UserMemoryService(jdbc));
        doReturn(true).when(memories).enabled("alice");
        var facts = new ArrayList<Memory>();
        facts.add(fact("unrelated", "保留其他事实"));
        doAnswer(c -> new Inventory(List.copyOf(facts), true, CAP)).when(memories).list("alice");
        doAnswer(c -> { var f = fact("stable-id", c.<Draft>getArgument(1).fact()); facts.add(f); return f; })
                .when(memories).add(eq("alice"), any());
        doAnswer(c -> { facts.removeIf(f -> f.id().equals(c.getArgument(1))); var f = fact(c.getArgument(1), c.<Draft>getArgument(2).fact()); facts.add(0, f); return f; })
                .when(memories).edit(eq("alice"), anyString(), any());
        doAnswer(c -> { facts.removeIf(f -> f.id().equals(c.getArgument(1))); return null; })
                .when(memories).delete(eq("alice"), anyString());
        memories.upsertPrefixed("alice", Kind.GOAL, "目标职业：", "工程师");
        memories.upsertPrefixed("alice", Kind.GOAL, "目标职业：", "工程师");
        verify(memories, times(1)).add(eq("alice"), any());
        verify(memories, never()).edit(anyString(), anyString(), any());
        memories.upsertPrefixed("alice", Kind.GOAL, "目标职业：", "数据分析师");
        assertThat(facts).filteredOn(f -> f.id().equals("stable-id")).extracting(Memory::fact).containsExactly("目标职业：数据分析师");
        memories.upsertPrefixed("alice", Kind.GOAL, "目标职业：", "");
        assertThat(facts).extracting(Memory::id).containsExactly("unrelated");
    }

    @Test void disabledMemorySuppressesAutomaticWriteAndRead() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM users"), eq(String.class), eq("alice"))).thenReturn(List.of("alice"));
        var memories = spy(new UserMemoryService(jdbc));
        doReturn(false).when(memories).enabled("alice");
        memories.upsertPrefixed("alice", Kind.GOAL, "目标职业：", "工程师");
        verify(memories, never()).list(anyString());
        verify(memories, never()).add(anyString(), any());
    }

    private static Memory fact(String id, String value) {
        return new Memory(id, Kind.GOAL, value, Source.MANUAL, null, null, "before", "before");
    }
}
