package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class GuestIdentityService implements CurrentUser {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public GuestIdentityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Credential(String accessToken, Instant expiresAt) {}

    @Transactional
    public Credential create() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String userId = IdGenerator.next("usr");
        Instant expires = Instant.now().plus(30, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname) VALUES (?,?,?,?)",
                userId, userId + "@guest.invalid", "!guest-no-password-login", "访客");
        jdbc.update("INSERT INTO guest_sessions(token_hash,user_id,expires_at) VALUES (?,?,?)",
                hash(token), userId, Timestamp.from(expires));
        return new Credential(token, expires);
    }

    @Override
    public String require(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        var users = jdbc.queryForList("""
                SELECT s.user_id FROM guest_sessions s JOIN users u ON u.id=s.user_id
                WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE'
                """, String.class, hash(authorization.substring(7)));
        if (users.isEmpty()) throw new BusinessException(ErrorCode.TOKEN_EXPIRED,
                "访客身份已失效，请保留当前浏览器数据并联系管理员恢复访问");
        return users.get(0);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
