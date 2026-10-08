package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.config.OfferPilotProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {
    private OfferPilotProperties properties(String secret) {
        return new OfferPilotProperties(new OfferPilotProperties.Security(secret, Duration.ofHours(12),
                Duration.ofDays(7), ""), null, null, null, null, null, null);
    }

    @Test
    void refusesMissingShortAndPublicPlaceholderSecrets() {
        for (String secret : new String[]{null, "", " ", "short", "a".repeat(32),
                "change-me-generate-with-openssl-rand-base64-64", "change-me-" + "x".repeat(64)}) {
            assertThatThrownBy(() -> new JwtTokenService(properties(secret)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
        }
    }

    @Test
    void issuesTwelveHourTokensAndRejectsWrongSigningKey() {
        var tokens = new JwtTokenService(properties("test-only-signing-key-" + "a".repeat(64)));
        var claims = tokens.parseAndVerify(tokens.issue("usr_private", List.of("USER"), "User"));
        assertThat(claims.getSubject()).isEqualTo("usr_private");
        assertThat(claims.get("roles", List.class)).containsExactly("USER");
        assertThat(Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant()))
                .isEqualTo(Duration.ofHours(12));
        var other = new JwtTokenService(properties("test-only-other-key-" + "b".repeat(64)));
        assertThatThrownBy(() -> tokens.parseAndVerify(other.issue("usr_private", List.of("USER"), "User")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID));
    }
}
