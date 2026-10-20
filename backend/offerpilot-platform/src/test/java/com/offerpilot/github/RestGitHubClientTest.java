package com.offerpilot.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** In-memory HTTP stub exercises the actual body subscriber; never opens a socket. */
class RestGitHubClientTest {
    final HttpClient http = mock(HttpClient.class);
    final List<HttpRequest> requests = new ArrayList<>();
    record Reply(int status, String body, Map<String, List<String>> headers) {
        Reply(int status, String body) { this(status, body, Map.of()); }
    }
    @SuppressWarnings("unchecked") void replies(Reply... values) {
        var queue = new ArrayDeque<>(List.of(values));
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0); requests.add(request);
            var reply = queue.remove();
            HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
            var headers = HttpHeaders.of(reply.headers(), (a,b) -> true);
            when(info.headers()).thenReturn(headers);
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            var subscriber = handler.apply(info);
            subscriber.onSubscribe(mock(Flow.Subscription.class));
            subscriber.onNext(List.of(ByteBuffer.wrap(reply.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            subscriber.onComplete();
            return subscriber.getBody().thenApply(body -> {
                var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
                when(response.body()).thenReturn(body); when(response.headers()).thenReturn(headers);
                when(response.statusCode()).thenReturn(reply.status()); return response;
            }).toCompletableFuture();
        });
    }
    @Test void readsOnlyPublicRepositoriesAndAuthorFilteredDefaultBranchActivity() {
        String content = Base64.getEncoder().encodeToString("Builds a Java service".getBytes());
        replies(new Reply(200,"[{\"private\":true,\"full_name\":\"fixture/private\"},{\"private\":false,\"full_name\":\"fixture/project\",\"stargazers_count\":7}]"),
                new Reply(200,"{\"Java\":120,\"Python\":80}"),new Reply(200,"[{\"sha\":\"a\"},{\"sha\":\"b\"}]"),
                new Reply(200,"{\"encoding\":\"base64\",\"size\":20,\"content\":\""+content+"\"}"));
        var source = new RestGitHubClient(http, new ObjectMapper()).fetch("fixture", "fixture_only");
        assertThat(source.repositories()).hasSize(1);
        var repo = source.repositories().get(0);
        assertThat(repo.stars()).isEqualTo(7); assertThat(repo.commits90d()).isEqualTo(2);
        assertThat(repo.languages()).containsEntry("Java",120L); assertThat(repo.readme()).contains("Java service");
        assertThat(requests).allMatch(r -> r.method().equals("GET") && r.uri().getHost().equals("api.github.com"));
        assertThat(requests).allMatch(r -> r.headers().firstValue("Authorization").orElse("").equals("Bearer fixture_only"));
        assertThat(requests.get(2).uri().getQuery()).contains("author=fixture", "since=", "per_page=100");
    }
    @Test void rateLimitAuthenticationAndUnavailableHaveSafeDistinctErrors() {
        for (var reply : List.of(new Reply(429,"credential must not echo"),
                new Reply(403,"sensitive",Map.of("X-RateLimit-Remaining",List.of("0"))),
                new Reply(403,"{\"message\":\"You have exceeded a secondary rate limit.\"}"),
                new Reply(401,"sensitive"),new Reply(404,"sensitive"),new Reply(503,"sensitive"),new Reply(302,"sensitive"))) {
            replies(reply);
            var failure = catchThrowableOfType(() -> new RestGitHubClient(http,new ObjectMapper()).fetch("fixture",null), GitHubClient.Failure.class);
            assertThat(failure.getMessage()).doesNotContain("sensitive","credential");
            assertThat(failure.status()).isEqualTo(reply.status()==429 || reply.status()==403 ? "RATE_LIMITED"
                    : reply.status()==401 ? "AUTH_FAILED" : reply.status()==404 ? "NOT_FOUND" : "UNAVAILABLE");
        }
        assertThat(requests).hasSize(7);
    }
    @Test void emptyRepositoryAndMissingReadmeAreRealEmptyData() {
        replies(new Reply(200,"[{\"private\":false,\"stargazers_count\":0,\"full_name\":\"fixture/empty\"}]"),new Reply(200,"{}"),new Reply(409,"empty"),new Reply(404,"none"));
        var repo = new RestGitHubClient(http,new ObjectMapper()).fetch("fixture",null).repositories().get(0);
        assertThat(repo.commits90d()).isZero(); assertThat(repo.readme()).isEmpty();
        assertThat(requests).allMatch(r -> r.headers().firstValue("Authorization").isEmpty());
    }
    @Test void rejectsOversizedBodyForeignRepositoryAndInvalidUsername() {
        replies(new Reply(200,"x".repeat(600001)));
        assertThatThrownBy(() -> new RestGitHubClient(http,new ObjectMapper()).fetch("fixture",null)).isInstanceOf(GitHubClient.Failure.class);
        replies(new Reply(200,"[{\"private\":false,\"stargazers_count\":0,\"full_name\":\"other/repo\"}]"));
        assertThatThrownBy(() -> new RestGitHubClient(http,new ObjectMapper()).fetch("fixture",null)).isInstanceOf(GitHubClient.Failure.class);
        assertThatThrownBy(() -> new RestGitHubClient(http,new ObjectMapper()).fetch("../metadata",null)).isInstanceOf(GitHubClient.Failure.class);
        assertThat(requests).hasSize(2);
    }
    @Test void marksCommitAndRepositoryLimitsInsteadOfClaimingCompleteCounts() {
        var repos = new ArrayList<String>();
        var responses = new ArrayList<Reply>();
        for (int i=0;i<21;i++) repos.add("{\"private\":false,\"stargazers_count\":0,\"full_name\":\"fixture/r"+i+"\"}");
        responses.add(new Reply(200,"["+String.join(",",repos)+"]"));
        for (int i=0;i<20;i++) {
            responses.add(new Reply(200,"{}")); responses.add(new Reply(200,"["+String.join(",",Collections.nCopies(100,"{}"))+"]")); responses.add(new Reply(404,""));
        }
        replies(responses.toArray(Reply[]::new));
        var snapshot = new RestGitHubClient(http,new ObjectMapper()).fetch("fixture",null);
        assertThat(snapshot.repositoriesTruncated()).isTrue(); assertThat(snapshot.repositories()).hasSize(20);
        assertThat(snapshot.repositories()).allMatch(GitHubClient.Repository::commitsTruncated);
    }
}
