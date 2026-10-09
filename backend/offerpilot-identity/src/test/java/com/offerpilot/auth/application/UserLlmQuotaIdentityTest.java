package com.offerpilot.auth.application;

import com.offerpilot.config.OfferPilotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.Types;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class UserLlmQuotaIdentityTest {
    UserLlmQuotaIdentity identity(boolean guest, Integer preference) {
        var jdbc = new JdbcTemplate() {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                assertThat(sql).contains("guest_sessions", "LEFT JOIN user_preferences", "u.status='ACTIVE'");
                assertThat(args).containsExactly("verified-owner");
                try {
                    var metadata = new RowSetMetaDataImpl();
                    metadata.setColumnCount(2);
                    metadata.setColumnName(1, "guest"); metadata.setColumnType(1, Types.BOOLEAN);
                    metadata.setColumnName(2, "daily_token_limit"); metadata.setColumnType(2, Types.INTEGER);
                    var row = RowSetProvider.newFactory().createCachedRowSet();
                    row.setMetaData(metadata); row.moveToInsertRow(); row.updateBoolean(1, guest);
                    if (preference == null) row.updateNull(2); else row.updateInt(2, preference);
                    row.insertRow(); row.moveToCurrentRow(); row.beforeFirst(); row.next();
                    return List.of(mapper.mapRow(row, 0));
                } catch (java.sql.SQLException ex) { throw new IllegalStateException(ex); }
            }
        };
        var config = new OfferPilotProperties(null, null, null, null,
                new OfferPilotProperties.RateLimit(60, 2, 20, 200000, 50000, 15, 20, 20, 20, 20), null, null, null);
        return new UserLlmQuotaIdentity(jdbc, config);
    }
    @Test void guestWithoutPreferencesGetsGuestQuota() {
        assertThat(identity(true, null).policy("verified-owner").dailyTokenLimit()).isEqualTo(50000);
        assertThat(identity(true, 999999).policy("verified-owner").dailyTokenLimit()).isEqualTo(50000);
        assertThat(identity(true, null).policy("verified-owner").guest()).isTrue();
    }
    @Test void registeredUserGetsPreferenceOrConfiguredDefault() {
        assertThat(identity(false, 123).policy("verified-owner").dailyTokenLimit()).isEqualTo(123);
        assertThat(identity(false, 0).policy("verified-owner").dailyTokenLimit()).isZero();
        assertThat(identity(false, null).policy("verified-owner").dailyTokenLimit()).isEqualTo(200000);
        assertThat(identity(false, null).policy("verified-owner").guest()).isFalse();
    }
}
