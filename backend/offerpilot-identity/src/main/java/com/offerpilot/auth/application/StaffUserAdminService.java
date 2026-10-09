package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 客服端账号管理：列出所有用户端账号（不含 STAFF）并启用/禁用。
 * 仅允许操作 USER 角色账号，STAFF 与自身无法通过本服务修改。
 */
@Service
public class StaffUserAdminService {
    private final JdbcTemplate jdbc;
    private final PasswordLoginService logins;

    public StaffUserAdminService(JdbcTemplate jdbc, PasswordLoginService logins) {
        this.jdbc = jdbc;
        this.logins = logins;
    }

    public record UserRow(String id, String email, String nickname, List<String> roles,
                          String status, Instant createdAt) {}

    public record UserPage(List<UserRow> items, long total, int page, int size) {}

    private record Target(String id, String status, String rolesCsv) {}

    /** 分页列出用户端账号（角色不含 STAFF），可隐藏有访客会话的账号。 */
    @Transactional(readOnly = true)
    public UserPage listUsers(String authorization, int page, int size, boolean hideGuests) {
        logins.requireStaffId(authorization);
        if (page < 1 || size < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "page 和 size 必须大于等于 1");
        }
        int pageSize = Math.min(size, 100);
        String filter = "NOT ('STAFF' = ANY(roles))"
                + (hideGuests ? " AND NOT EXISTS (SELECT 1 FROM guest_sessions g WHERE g.user_id = users.id)" : "");
        long total = jdbc.queryForObject("SELECT count(*) FROM users WHERE " + filter, Long.class);
        List<UserRow> items = jdbc.query("""
                SELECT id, email, nickname, array_to_string(roles, ',') AS roles_csv, status, created_at
                FROM users WHERE %s
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """.formatted(filter), (rs, i) -> new UserRow(rs.getString("id"), rs.getString("email"),
                rs.getString("nickname"),
                Arrays.stream(rs.getString("roles_csv").split(",")).filter(s -> !s.isBlank()).toList(),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()), pageSize, ((long) page - 1) * pageSize);
        return new UserPage(items, total, page, pageSize);
    }

    /** 启用/禁用用户端账号；target 必须是 USER 角色账号。 */
    @Transactional
    public UserRow setUserStatus(String authorization, String userId, String status) {
        logins.requireStaffId(authorization);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "status 仅允许 ACTIVE 或 DISABLED");
        }
        Target target;
        try {
            target = jdbc.queryForObject("""
                    SELECT id, status, array_to_string(roles, ',') AS roles_csv
                    FROM users WHERE id = ?
                    """, (rs, i) -> new Target(rs.getString("id"), rs.getString("status"),
                    rs.getString("roles_csv")), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        boolean isStaff = Arrays.stream(target.rolesCsv().split(",")).anyMatch("STAFF"::equals);
        if (isStaff) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "客服账号不允许通过用户端账号管理修改");
        }
        if (status.equals(target.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "ACTIVE".equals(status) ? "账号已是启用状态" : "账号已是禁用状态");
        }
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, userId);
        return findUser(userId);
    }

    private UserRow findUser(String userId) {
        return jdbc.queryForObject("""
                SELECT id, email, nickname, array_to_string(roles, ',') AS roles_csv, status, created_at
                FROM users WHERE id = ?
                """, (rs, i) -> new UserRow(rs.getString("id"), rs.getString("email"),
                rs.getString("nickname"),
                Arrays.stream(rs.getString("roles_csv").split(",")).filter(s -> !s.isBlank()).toList(),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()), userId);
    }
}
