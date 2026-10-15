package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.config.OfferPilotProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * JWT 令牌服务：生成、解析、比对校验。
 * 账号登录的访问令牌取 offerpilot.security.access-ttl（15 分钟），过期后用 HttpOnly refresh Cookie 续期；
 * 体验沙箱没有 refresh，其令牌以沙箱到期时间为准，最长 {@link #SANDBOX_TTL_CAP}。
 */
@Service
public class JwtTokenService {
    public static final Duration SANDBOX_TTL_CAP = Duration.ofHours(12);
    private final SecretKey key;
    private final Duration accessTtl;

    public JwtTokenService(OfferPilotProperties properties) {
        String secret = properties.security().jwtSecret();
        if (secret == null || secret.isBlank() || secret.trim().startsWith("change-me")) {
            throw new IllegalStateException("JWT_SECRET 必须配置为随机密钥，不能使用公开占位值");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 64) {
            throw new IllegalStateException("JWT_SECRET 至少需要 64 字节的随机密钥");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = properties.security().accessTtl();
    }

    /** 生成签名令牌，claims 携带用户 ID、角色与昵称。 */
    public String issue(String userId, List<String> roles, String nickname) {
        return issueUntil(userId, roles, nickname, Instant.now().plus(accessTtl));
    }

    /** Sandbox credentials (no refresh) never outlive the sandbox or {@link #SANDBOX_TTL_CAP}. */
    public String issue(String userId, List<String> roles, String nickname, Instant expiresAt) {
        return sign(userId, roles, nickname, earliest(expiresAt, Instant.now().plus(SANDBOX_TTL_CAP)));
    }

    private static Instant earliest(Instant a, Instant b) { return a.isBefore(b) ? a : b; }

    private String issueUntil(String userId, List<String> roles, String nickname, Instant expiresAt) {
        return sign(userId, roles, nickname, earliest(expiresAt, Instant.now().plus(accessTtl)));
    }

    private String sign(String userId, List<String> roles, String nickname, Instant expiry) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("roles", roles)
                .claim("nickname", nickname)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析并比对校验签名与有效期；无效返回 20003，过期返回 20002。 */
    public Claims parseAndVerify(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException ex) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }

    public Duration accessTtl() {
        return accessTtl;
    }
}
