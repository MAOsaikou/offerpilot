package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.quota.LlmQuotaIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserLlmQuotaIdentity implements LlmQuotaIdentity {
    private final JdbcTemplate jdbc;
    private final OfferPilotProperties properties;
    public UserLlmQuotaIdentity(JdbcTemplate jdbc, OfferPilotProperties properties) { this.jdbc = jdbc; this.properties = properties; }
    @Override public Policy policy(String owner) {
        var rows = jdbc.query("""
                SELECT EXISTS(SELECT 1 FROM guest_sessions s WHERE s.user_id=u.id) AS guest,
                       p.daily_token_limit
                FROM users u LEFT JOIN user_preferences p ON p.user_id=u.id
                WHERE u.id=? AND u.status='ACTIVE'
                """, (rs, row) -> {
            boolean guest = rs.getBoolean("guest");
            Number preferred = (Number) rs.getObject("daily_token_limit");
            long daily = guest ? properties.rateLimit().guestDailyTokenLimit()
                    : preferred == null ? properties.rateLimit().defaultDailyTokenLimit() : preferred.longValue();
            return new Policy(guest, daily);
        }, owner);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return rows.get(0);
    }
}
