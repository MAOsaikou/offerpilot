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
        for (String value : List.of("{\"fixture/project\":[\"Improved performance 99%\"]}","{\"unknown/project\":[\"Java API service.\"]}","{\"fixture/project\":[123]}","{\"fixture/project\":\"Java API service.\"}"))
            assertThat(GitHubHighlights.validate(source,json.readTree(value))).isEmpty();
        assertThatThrownBy(() -> GitHubHighlights.validate(source,json.readTree("[]"))).isInstanceOf(RuntimeException.class);
    }
    @Test void dropsOnlyNonCompliantQuotesWhenMixedWithValidOnes() throws Exception {
        String longParagraph = "L".repeat(281);
        String readme = "Intro line.\n" + longParagraph + "\nSpring Boot backend with RAG retrieval.\nVue 3 frontend.\nExtra fourth line.";
        var mixed = new GitHubClient.Snapshot(List.of(
                new GitHubClient.Repository("fixture/project","https://github.com/fixture/project",false,3,Map.of("Java",10L),1,false,readme),
                new GitHubClient.Repository("fixture/other","https://github.com/fixture/other",false,0,Map.of(),0,false,"Only fabricated claims here.")),false,Instant.now());
        var node = json.createObjectNode();
        node.putArray("fixture/project").add("Intro line.").add(longParagraph).add("Invented 10x speedup").add(7)
                .add("Spring Boot backend with RAG retrieval.").add("Intro line.").add("Vue 3 frontend.").add("Extra fourth line.");
        node.putArray("fixture/other").add("Not in the readme at all");
        node.putArray("unknown/project").add("Intro line.");
        assertThat(GitHubHighlights.validate(mixed,node)).containsExactly(
                Map.entry("fixture/project",List.of("Intro line.","Spring Boot backend with RAG retrieval.","Vue 3 frontend.")));
        String exact280 = "M".repeat(280);
        var bound = new GitHubClient.Snapshot(List.of(new GitHubClient.Repository("fixture/project","https://github.com/fixture/project",false,3,Map.of(),1,false,exact280)),false,Instant.now());
        assertThat(GitHubHighlights.validate(bound,json.readTree("{\"fixture/project\":[\"" + exact280 + "\"]}"))).containsEntry("fixture/project",List.of(exact280));
    }
    @Test void usesFlashAndPropagatesProviderFailureToStatisticsFallback() {
        var gateway = mock(ModelGateway.class);
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"fixture/project\":[\"Java API service.\"]}"))))));
        assertThat(new GitHubHighlights(gateway,json).extract("owner",source)).containsKey("fixture/project");
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(Flux.error(new IllegalStateException("fixture provider failure")));
        assertThatThrownBy(() -> new GitHubHighlights(gateway,json).extract("owner",source)).isInstanceOf(RuntimeException.class);
    }
}
