package com.offerpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "offerpilot")
public record OfferPilotProperties(
        Security security,
        Demo demo,
        Storage storage,
        Encryption encryption,
        RateLimit rateLimit,
        Llm llm,
        Mcp mcp,
        @DefaultValue StaffBootstrap staffBootstrap
) {

    public record StaffBootstrap(@DefaultValue("") String email, @DefaultValue("") String password) {
        public StaffBootstrap {
            email = email == null ? "" : email;
            password = password == null ? "" : password;
        }
    }

    public record Security(
            String jwtSecret,
            @DefaultValue("12h") Duration accessTtl,
            @DefaultValue("7d") Duration refreshTtl,
            @DefaultValue("") String corsOrigins
    ) {
        public List<String> corsOriginList() {
            if (corsOrigins == null || corsOrigins.isBlank()) {
                return List.of();
            }
            List<String> origins = new ArrayList<>();
            for (String origin : corsOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty()) {
                    origins.add(trimmed);
                }
            }
            return List.copyOf(origins);
        }
    }

    public record Demo(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("demo@offerpilot.local") String email,
            String password,
            @DefaultValue("0 0 4 * * *") String resetCron
    ) {
    }

    public record Storage(
            @DefaultValue("http://localhost:9000") String endpoint,
            @DefaultValue("http://localhost:9000") String publicEndpoint,
            String accessKey,
            String secretKey,
            @DefaultValue("offerpilot") String bucket
    ) {
    }

    public record Encryption(String key) {
    }

    public record RateLimit(
            @DefaultValue("60") int perUserPerMinute,
            @DefaultValue("2") int concurrentRunsPerUser,
            @DefaultValue("20") int guestPerUserPerMinute,
            @DefaultValue("200000") int defaultDailyTokenLimit,
            @DefaultValue("50000") int guestDailyTokenLimit,
            @DefaultValue("15") int authGuestPerMinute,
            @DefaultValue("20") int authLoginPerMinute,
            @DefaultValue("20") int authRegisterPerMinute,
            @DefaultValue("20") int authStaffLoginPerMinute,
            @DefaultValue("20") int authDemoLoginPerMinute
    ) {
        public RateLimit {
            if (perUserPerMinute < 1 || guestPerUserPerMinute < 1 || defaultDailyTokenLimit < 0 || guestDailyTokenLimit < 0) {
                throw new IllegalArgumentException("LLM request limits must be positive and token limits non-negative");
            }
            if (authGuestPerMinute < 1 || authLoginPerMinute < 1
                    || authRegisterPerMinute < 1 || authStaffLoginPerMinute < 1 || authDemoLoginPerMinute < 1) {
                throw new IllegalArgumentException("Auth rate limits must be positive");
            }
        }
    }

    public record Llm(
            @DefaultValue("FLASH") String defaultTier,
            Map<String, Tier> tiers,
            Map<String, List<String>> fallback
    ) {
        public Llm {
            if (tiers == null) {
                tiers = new LinkedHashMap<>();
            }
            if (fallback == null) {
                fallback = new LinkedHashMap<>();
            }
        }

        public record Tier(
                String provider,
                String model,
                @DefaultValue("60s") Duration timeout,
                @DefaultValue("false") boolean supportsVision,
                @DefaultValue("disabled") String thinking,
                @DefaultValue("8192") int maxTokens
        ) {
            public Tier(String provider, String model, Duration timeout, boolean supportsVision) {
                this(provider, model, timeout, supportsVision, "disabled", 8192);
            }
            @org.springframework.boot.context.properties.bind.ConstructorBinding
            public Tier {
                if (!"enabled".equals(thinking) && !"disabled".equals(thinking))
                    throw new IllegalArgumentException("LLM thinking must be enabled or disabled");
                if (timeout == null || timeout.isZero() || timeout.isNegative())
                    throw new IllegalArgumentException("LLM timeout must be positive");
                if (maxTokens < 1) throw new IllegalArgumentException("LLM maxTokens must be positive");
            }
        }
    }

    public record Mcp(
            @DefaultValue("http://localhost:7001") String jobsUrl,
            String searchUrl,
            String searchApiKey,
            @DefaultValue("https://api.githubcopilot.com/mcp/") String githubUrl,
            @DefaultValue("false") boolean serverEnabled,
            @DefaultValue("") String serverApiKey
    ) {
    }
}
