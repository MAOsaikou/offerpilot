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
 * 失效时间取 offerpilot.security.access-ttl（当前 12 小时）。
 */
@Service
public class JwtTokenService {
    private final SecretKey key;
    private final Duration accessTtl;

    public JwtTokenService(OfferPilotProperties properties) {
        String secret = properties.security().jwtSecret();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("offerpilot.security.jwt-secret 至少需要 32 字节（HS256）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = properties.security().accessTtl();
    }

    /** 生成签名令牌，claims 携带用户 ID、角色与昵称。 */
    public String issue(String userId, List<String> roles, String nickname) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("roles", roles)
                .claim("nickname", nickname)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
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
