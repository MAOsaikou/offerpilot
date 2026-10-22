package com.offerpilot.github;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only, injectable transport. No credential is ever part of a result. */
public interface GitHubClient {
    record Repository(String name, String url, boolean fork, long stars, Map<String, Long> languages,
                      int commits90d, boolean commitsTruncated, String readme) {}
    record Snapshot(List<Repository> repositories, boolean repositoriesTruncated, Instant since, String notice) {
        public Snapshot(List<Repository> repositories, boolean repositoriesTruncated, Instant since) {
            this(repositories, repositoriesTruncated, since, "");
        }
    }
    Snapshot fetch(String username, String token);

    final class Failure extends RuntimeException {
        private final String status;
        public Failure(String status, String safeMessage) { super(safeMessage); this.status = status; }
        public String status() { return status; }
    }
}
