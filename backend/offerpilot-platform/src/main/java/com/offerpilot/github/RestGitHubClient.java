package com.offerpilot.github;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed GitHub origin, no redirect/remote URL traversal, bounded requests and total budget. */
@Component
public class RestGitHubClient implements GitHubClient {
    private final HttpClient http;
    private final ObjectMapper json;
    @Autowired public RestGitHubClient(ObjectMapper json) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build(), json);
    }
    public RestGitHubClient(HttpClient http, ObjectMapper json) { this.http = http; this.json = json; }
    public static boolean validUsername(String name) {
        return name != null && name.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?") && !name.contains("--");
    }
    private static final int RESERVE = 5;
    private static final String PARTIAL = "GitHub 请求预算不足或中途限流，仅保留已完整抓取的仓库；可稍后重试或配置公开仓库只读 Token。";
    /** Request-local: concurrent users must never share another credential's remaining budget. */
    private static final class Budget {
        int remaining = Integer.MAX_VALUE;
        int requests;
        final int maxRequests;
        Budget(boolean authenticated) { maxRequests = authenticated ? 61 : 46; }
        boolean canFetchRepository() { return requests + 3 <= maxRequests && remaining >= RESERVE + 3; }
        void observe(HttpHeaders headers) {
            headers.firstValue("X-RateLimit-Remaining").ifPresent(value -> {
                try { remaining = Math.min(remaining, Math.max(0, Integer.parseInt(value))); }
                catch (NumberFormatException ignored) { /* Keep the local request cap. */ }
            });
        }
    }
    @Override public Snapshot fetch(String username, String token) {
        if (!validUsername(username)) throw new Failure("INVALID", "GitHub 用户名格式无效");
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        Instant since = Instant.now().minus(Duration.ofDays(90));
        boolean authenticated = token != null && !token.isBlank();
        int maxRepositories = authenticated ? 20 : 15;
        var budget = new Budget(authenticated);
        var repos = get("/users/" + username + "/repos?type=owner&sort=pushed&direction=desc&per_page=" + (maxRepositories + 1), token, deadline, false, budget);
        if (!repos.isArray()) throw unavailable();
        var result = new ArrayList<Repository>();
        String notice = "";
        for (var repo : repos) {
            if (result.size() == maxRepositories) break;
            if (!budget.canFetchRepository()) { notice = PARTIAL; break; }
            // Even an overprivileged PAT must never introduce private data.
            if (!repo.path("private").isBoolean()) throw unavailable();
            if (repo.path("private").asBoolean()) continue;
            if (!repo.path("stargazers_count").isIntegralNumber() || !repo.path("stargazers_count").canConvertToLong()
                    || repo.path("stargazers_count").asLong() < 0) throw unavailable();
            String full = repo.path("full_name").asText();
            if (!full.matches("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+") || !full.split("/")[0].equalsIgnoreCase(username)) throw unavailable();
            String path = "/repos/" + full;
            try {
                var languages = get(path + "/languages", token, deadline, false, budget);
                if (!languages.isObject()) throw unavailable();
                var bytes = new TreeMap<String, Long>();
                languages.fields().forEachRemaining(e -> {
                    if (e.getKey().length() > 100 || !e.getValue().isIntegralNumber() || !e.getValue().canConvertToLong()
                            || e.getValue().asLong() < 0) throw unavailable();
                    if (e.getValue().asLong() > 0) bytes.put(e.getKey(), e.getValue().asLong());
                });
                var commits = get(path + "/commits?author=" + username + "&since=" + since + "&per_page=100", token, deadline, true, budget);
                if (!commits.isArray() || commits.size() > 100) throw unavailable();
                var readme = get(path + "/readme", token, deadline, true, budget);
                String text = "";
                if ("base64".equals(readme.path("encoding").asText()) && readme.path("size").asLong() <= 100_000) {
                    try {
                        text = new String(Base64.getMimeDecoder().decode(readme.path("content").asText()), StandardCharsets.UTF_8);
                        if (text.length() > 8000) text = text.substring(0, 8000);
                    } catch (IllegalArgumentException ignored) { /* No README evidence when invalid. */ }
                }
                result.add(new Repository(full, "https://github.com/" + full, repo.path("fork").asBoolean(),
                        repo.path("stargazers_count").asLong(), Map.copyOf(bytes), commits.size(), commits.size() == 100, text));
            } catch (Failure ex) {
                if (!"RATE_LIMITED".equals(ex.status()) || result.isEmpty()) throw ex;
                notice = PARTIAL;
                break;
            }
        }
        return new Snapshot(List.copyOf(result), repos.size() > result.size(), since, notice);
    }
    private JsonNode get(String path, String token, long deadline, boolean optional, Budget budget) {
        if (budget.requests >= budget.maxRequests || budget.remaining <= RESERVE)
            throw new Failure("RATE_LIMITED", PARTIAL);
        budget.requests++;
        if (budget.remaining != Integer.MAX_VALUE) budget.remaining--;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw unavailable();
        var request = HttpRequest.newBuilder(URI.create("https://api.github.com" + path))
                .timeout(Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(5).toNanos())))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "OfferPilot");
        if (token != null && !token.isEmpty()) request.header("Authorization", "Bearer " + token);
        try {
            // ofByteArray completes only after the body; the request deadline includes reading it.
            // The subscriber cancels oversized bodies before they can be buffered unboundedly.
            var future = http.sendAsync(request.GET().build(), info -> new LimitedBodySubscriber(600_000));
            HttpResponse<byte[]> response;
            try { response = future.get(Math.min(remaining, Duration.ofSeconds(5).toNanos()), TimeUnit.NANOSECONDS); }
            finally { if (!future.isDone()) future.cancel(true); }
            budget.observe(response.headers());
            int code = response.statusCode();
            if (code == 429 || code == 403 && (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")
                    || response.headers().firstValue("Retry-After").isPresent() || rateLimitMessage(response.body())))
                throw new Failure("RATE_LIMITED", "GitHub 请求已限流，请稍后重试或配置公开仓库只读 Token");
            if (code == 401 || code == 403) throw new Failure("AUTH_FAILED", "GitHub Token 无效或权限不足，请更新或移除 Token");
            if (optional && (code == 404 && path.endsWith("/readme") || code == 409 && path.contains("/commits?"))) return json.createArrayNode();
            if (code == 404 && path.startsWith("/users/")) throw new Failure("NOT_FOUND", "未找到公开 GitHub 用户，请核对用户名");
            if (code != 200) throw unavailable();
            var body = json.readTree(response.body());
            if (body == null || !body.isContainerNode()) throw unavailable();
            return body;
        } catch (Failure ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception ex) { throw unavailable(); } // Never retain headers, response bodies or provider causes.
    }
    private boolean rateLimitMessage(byte[] body) {
        try { return json.readTree(body).path("message").asText().toLowerCase(Locale.ROOT).contains("rate limit"); }
        catch (Exception ex) { return false; }
    }
    private static Failure unavailable() { return new Failure("UNAVAILABLE", "GitHub 暂时不可达或响应异常，请稍后重试；未生成统计数据"); }
}
