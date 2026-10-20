package com.offerpilot.resume.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.github.GitHubClient;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import reactor.core.publisher.Flux;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GitHubHighlightsTest {
    final ObjectMapper json = new ObjectMapper();
    final GitHubClient.Snapshot source = new GitHubClient.Snapshot(List.of(new GitHubClient.Repository("fixture/project","https://github.com/fixture/project",false,3,Map.of("Java",10L),1,false,"Java API service. No performance benchmark.")),false,Instant.now());
    @Test void onlyVerbatimBoundedReadmeQuotesAreAccepted() throws Exception {
        assertThat(GitHubHighlights.validate(source,json.readTree("{\"fixture/project\":[\"Java API service.\"]}"))).containsEntry("fixture/project",List.of("Java API service."));
        for (String value : List.of("{\"fixture/project\":[\"Improved performance 99%\"]}","{\"unknown/project\":[]}","{\"fixture/project\":[123]}","[]"))
            assertThatThrownBy(() -> GitHubHighlights.validate(source,json.readTree(value))).isInstanceOf(RuntimeException.class);
    }
    @Test void usesFlashAndPropagatesProviderFailureToStatisticsFallback() {
        var gateway = mock(ModelGateway.class);
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"fixture/project\":[\"Java API service.\"]}"))))));
        assertThat(new GitHubHighlights(gateway,json).extract("owner",source)).containsKey("fixture/project");
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(Flux.error(new IllegalStateException("fixture provider failure")));
        assertThatThrownBy(() -> new GitHubHighlights(gateway,json).extract("owner",source)).isInstanceOf(RuntimeException.class);
    }
}
