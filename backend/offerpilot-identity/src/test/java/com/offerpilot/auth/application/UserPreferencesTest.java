package com.offerpilot.auth.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class UserPreferencesTest {
    @Test void defaultsValidationAndPartialUpdatesPreserveLanguageAndPrivacyAcrossEndpoints() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var values = new HashMap<String, Object[]>();
        when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenAnswer(call -> {
            String owner = call.getArgument(2);
            Object[] row = values.get(owner);
            if (row == null) return List.of();
            var rs = mock(ResultSet.class);
            if (call.<String>getArgument(0).startsWith("SELECT pii_masking")) when(rs.getBoolean(1)).thenReturn((Boolean) row[4]);
            else {
                when(rs.getString(1)).thenReturn((String) row[0]);
                when(rs.getString(2)).thenReturn((String) row[1]);
                when(rs.getString(3)).thenReturn((String) row[2]);
                when(rs.getBoolean(4)).thenReturn((Boolean) row[3]);
                when(rs.getBoolean(5)).thenReturn((Boolean) row[4]);
            }
            return List.of(call.<RowMapper<?>>getArgument(1).mapRow(rs, 0));
        });
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            String owner = call.getArgument(1);
            Object[] previous = values.getOrDefault(owner, new Object[]{"FLASH", "system", "zh-CN", false, true});
            Object language = call.getArgument(4), pii = call.getArgument(5);
            values.put(owner, new Object[]{call.getArgument(2), call.getArgument(3), language == null ? previous[2] : language,
                    true, pii == null ? previous[4] : pii});
            return 1;
        });
        when(jdbc.update(anyString(), anyString(), anyBoolean())).thenAnswer(call -> {
            String owner = call.getArgument(1);
            var row = values.computeIfAbsent(owner, ignored -> new Object[]{"FLASH", "system", "zh-CN", false, true});
            row[4] = call.getArgument(2); return 1;
        });
        var service = new UserPreferences(jdbc);
        var privacy = new PrivacySettings(jdbc);
        assertThat(service.get("owner")).isEqualTo(new UserPreferences.View("FLASH", "system", "zh-CN", false, true));
        assertThat(privacy.enabled("owner")).isTrue();
        service.save("owner", new UserPreferences.Selection("PRO", "dark", "en-US", false));
        assertThat(service.language("owner")).isEqualTo("en-US");
        assertThat(service.instruction("owner")).contains("Reply in English", "Safety");
        assertThat(privacy.enabled("owner")).isFalse();
        service.save("owner", new UserPreferences.Selection("FLASH", "light"));
        assertThat(service.get("owner").language()).isEqualTo("en-US");
        assertThat(privacy.enabled("owner")).isFalse();
        privacy.save("owner", new PrivacySettings.Selection(true));
        assertThat(service.get("owner").piiMasking()).isTrue();
        assertThat(service.get("owner").language()).isEqualTo("en-US");
        assertThat(service.get("stranger").piiMasking()).isTrue();
        assertThat(service.language("stranger")).isEqualTo("zh-CN");
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new UserPreferences.Selection("FLASH", "system", "en-US", false))).isEmpty();
            assertThat(validator.validate(new UserPreferences.Selection("FLASH", "system", "fr-FR", true))).isNotEmpty();
            assertThat(validator.validate(new UserPreferences.Selection("FLASH", "system", "", true))).isNotEmpty();
            assertThat(validator.validate(new UserPreferences.Selection("PRO", "light"))).isEmpty();
        }
    }
}
