package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import io.jsonwebtoken.Claims;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 邮箱 + 密码登录：用户端要求 USER 角色，客服端要求 STAFF 角色。
 * 登录成功签发 JWT（见 {@link JwtTokenService}）。
 */
@Service
public class PasswordLoginService {
    private static final String ROLE_USER = "USER";
    private static final String ROLE_STAFF = "STAFF";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;

    public PasswordLoginService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    public record UserView(String id, String email, String nickname, List<String> roles) {}

    public record LoginResponse(String accessToken, long expiresIn, UserView user) {}

    public record VerifyResponse(boolean valid, String userId, List<String> roles, String nickname,
                                 Instant issuedAt, Instant expiresAt) {}

    private record Account(String id, String email, String passwordHash, String nickname, List<String> roles, String status) {}

    /** 用户端登录。 */
    @Transactional(readOnly = true)
    public LoginResponse login(String email, String password) {
        Account account = loadAndVerify(email, password);
        requireRole(account, ROLE_USER);
        return issue(account);
    }

    /** 客服端登录，账号必须包含 STAFF 角色。 */
    @Transactional(readOnly = true)
    public LoginResponse staffLogin(String email, String password) {
        Account account = loadAndVerify(email, password);
        requireRole(account, ROLE_STAFF);
        return issue(account);
    }

    /** 解析并比对校验 Bearer 令牌：签名、有效期，以及用户在数据库中仍然有效。 */
    @Transactional(readOnly = true)
    public VerifyResponse verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        Claims claims = tokens.parseAndVerify(authorization.substring(7).trim());
        String userId = claims.getSubject();
        List<String> roles = readRoles(claims);
        String status;
        try {
            status = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "令牌对应的用户不存在");
        }
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "令牌对应的用户已停用");
        }
        Date issuedAt = claims.getIssuedAt();
        return new VerifyResponse(true, userId, roles, claims.get("nickname", String.class),
                issuedAt == null ? null : issuedAt.toInstant(),
                claims.getExpiration() == null ? null : claims.getExpiration().toInstant());
    }

    /**
     * 解析 Bearer 令牌并要求其属于有效的 STAFF 账号：验签、有效期、角色、账号状态四重比对。
     * 返回客服用户 ID；不满足条件分别抛 20001/20002/20003/20004。
     */
    @Transactional(readOnly = true)
    public String requireStaffId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        Claims claims = tokens.parseAndVerify(authorization.substring(7).trim());
        List<String> roles = readRoles(claims);
        if (!roles.contains(ROLE_STAFF)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要客服账号权限");
        }
        String userId = claims.getSubject();
        String status;
        try {
            status = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "令牌对应的用户不存在");
        }
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "令牌对应的用户已停用");
        }
        return userId;
    }

    /** 用户端注册：仅需邮箱 + 密码，成功后直接签发令牌（自动登录）。 */
    @Transactional
    public LoginResponse register(String email, String password) {
        String normalized = email == null ? "" : email.trim();
        if (!normalized.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || normalized.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "邮箱格式不正确");
        }
        if (password == null || password.length() < 6 || password.length() > 72) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码长度需为 6-72 位");
        }
        String userId = IdGenerator.next("usr");
        String nickname = normalized.substring(0, normalized.indexOf('@'));
        String passwordHash = passwordEncoder.encode(password);
        try {
            // roles 使用表默认 '{USER}'，status 默认 ACTIVE
            jdbc.update("INSERT INTO users(id,email,password_hash,nickname) VALUES (?,?,?,?)",
                    userId, normalized, passwordHash, nickname);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.EMAIL_TAKEN);
        }
        return issue(new Account(userId, normalized, passwordHash, nickname, List.of(ROLE_USER), "ACTIVE"));
    }

    private Account loadAndVerify(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<Account> found = jdbc.query("""
                SELECT id, email, password_hash, nickname, array_to_string(roles, ',') AS roles_csv, status
                FROM users WHERE email = ?
                """, (rs, i) -> new Account(rs.getString("id"), rs.getString("email"),
                rs.getString("password_hash"), rs.getString("nickname"),
                Arrays.stream(rs.getString("roles_csv").split(",")).filter(s -> !s.isBlank()).toList(),
                rs.getString("status")), email.trim());
        if (found.isEmpty() || !passwordEncoder.matches(password, found.get(0).passwordHash())) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }
        Account account = found.get(0);
        if (!"ACTIVE".equals(account.status())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被停用");
        }
        return account;
    }

    private void requireRole(Account account, String role) {
        if (!account.roles().contains(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    ROLE_STAFF.equals(role) ? "该账号不是客服账号" : "该账号不是用户账号");
        }
    }

    private LoginResponse issue(Account account) {
        String token = tokens.issue(account.id(), account.roles(), account.nickname());
        return new LoginResponse(token, tokens.accessTtl().toSeconds(),
                new UserView(account.id(), account.email(), account.nickname(), account.roles()));
    }

    @SuppressWarnings("unchecked")
    private static List<String> readRoles(Claims claims) {
        Object raw = claims.get("roles");
        return raw instanceof List<?> list ? (List<String>) list : List.of();
    }
}
