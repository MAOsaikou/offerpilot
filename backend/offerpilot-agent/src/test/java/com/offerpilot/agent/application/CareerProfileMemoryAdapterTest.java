package com.offerpilot.agent.application;

import com.offerpilot.memory.application.UserMemoryService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.offerpilot.memory.application.UserMemoryService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CareerProfileMemoryAdapterTest {
    @Test void onlyReadsMatchingBoundedPrefixesForTheOwnerAndHonorsDisabledMemory() {
        var memories = mock(UserMemoryService.class);
        var adapter = new CareerProfileMemoryAdapter(memories);
        when(memories.enabled("alice")).thenReturn(true);
        when(memories.list("alice")).thenReturn(new Inventory(List.of(
                fact("目标职业：数据分析师"), fact("目标职业：旧目标"), fact("意向城市：成都"),
                fact("专业：" + "长".repeat(101)), fact("专业：统计学"), fact("我擅长Java")), true, CAP));
        var seed = adapter.read("alice");
        assertThat(seed.target()).isEqualTo("数据分析师");
        assertThat(seed.city()).isEqualTo("成都");
        assertThat(seed.major()).isEqualTo("统计学");
        assertThat(adapter.read("bob").target()).isEmpty();
        adapter.sync("bob", "目标", "城市", "专业", "IEC");
        verify(memories, never()).list("bob");
        verify(memories, never()).upsertPrefixed(eq("bob"), any(), any(), any());
    }
    @Test void assessmentConfirmationDoesNotEraseOtherMemoryOnlyFacts() {
        var memories = mock(UserMemoryService.class);
        when(memories.enabled("alice")).thenReturn(true);
        var adapter = new CareerProfileMemoryAdapter(memories);
        adapter.syncAssessment("alice", "", "IER");
        verify(memories).upsertPrefixed("alice", Kind.PREFERENCE, "兴趣码：", "IER");
        verify(memories, times(1)).upsertPrefixed(anyString(), any(), anyString(), anyString());
    }
    private static Memory fact(String text) { return new Memory(text, Kind.OTHER, text, Source.MANUAL, null, null, "", ""); }
}
