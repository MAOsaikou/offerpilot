package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.config.OfferPilotProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AC-01 refresh tokens: 256-bit random values; only their SHA-256 is stored. Every refresh rotates the
 * token. Presenting an already-rotated token after a short grace window (concurrent tabs) is treated as
 * theft and deletes every refresh token of that user. Logout and theft delete rows instead of revoking
 * them, so a revoked row always means "rotated" (and may be honoured once within the grace window).
 */
@Service
public class RefreshTokenService {
    /** Two tabs may race the same cookie; the loser still gets an access token but no new cookie. */
    static final Duration CONCURRENT_GRACE = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Issued(String token, Duration ttl) {}
    /** next is null when a concurrent refresh already rotated the cookie (keep the browser's newer one). */
    public record Rotation(String userId, Issued next) {}

    private final JdbcTemplate jdbc;
    private final Duration ttl;

    public RefreshTokenService(JdbcTemplate jdbc, OfferPilotProperties properties) {
        this.jdbc = jdbc;
        this.ttl = properties.security().refreshTtl();
    }

    @Transactional
    public Issued issue(String userId, String userAgent) {
        String token = random();
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id=? AND expires_at < now()", userId);
        jdbc.update("INSERT INTO refresh_tokens(id,user_id,token_hash,expires_at,user_agent) VALUES (?,?,?,?,?)",
                IdGenerator.next("rt"), userId, hash(token), Timestamp.from(Instant.now().plus(ttl)),
                userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())));
        return new Issued(token, ttl);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public Rotation rotate(String token, String userAgent) {
        if (token == null || token.isBlank() || token.length() > 100) throw invalid();
        var rows = jdbc.query("SELECT user_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash=? FOR UPDATE",
                (rs, n) -> new Object[]{rs.getString(1), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()}, hash(token));
        if (rows.isEmpty()) throw invalid();
        String userId = (String) rows.get(0)[0];
        Instant expires = (Instant) rows.get(0)[1], revoked = (Instant) rows.get(0)[2];
        if (expires.isBefore(Instant.now())) throw invalid();
        if (revoked != null) {
            if (revoked.isAfter(Instant.now().minus(CONCURRENT_GRACE))) return new Rotation(userId, null);
            // Reuse of a rotated token: someone else holds a copy. End every session of this user.
            // Delete rather than revoke, so revoked rows keep meaning "rotated" for the grace check.
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id=?", userId);
            throw invalid();
        }
        jdbc.update("UPDATE refresh_tokens SET revoked_at=now() WHERE token_hash=?", hash(token));
        return new Rotation(userId, issue(userId, userAgent));
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank() && token.length() <= 100)
            jdbc.update("DELETE FROM refresh_tokens WHERE token_hash=?", hash(token));
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.TOKEN_INVALID, "登录已过期，请重新登录");
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
