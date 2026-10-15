package com.offerpilot.auth.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * AC-03 account preferences that follow the user across browsers: default chat model tier and theme.
 * {@code saved} is false until the user saves once, so a browser keeps its local theme instead of being
 * overwritten by the table default. PII masking and memory have their own settings endpoints.
 */
@Service
public class UserPreferences {
    public record Selection(@NotNull @Pattern(regexp = "FLASH|PRO") String defaultModelTier,
                            @NotNull @Pattern(regexp = "light|dark|system") String theme) {}
    public record View(String defaultModelTier, String theme, String language, boolean saved) {}
    private final JdbcTemplate jdbc;

    public UserPreferences(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public View get(String owner) {
        var rows = jdbc.query("SELECT default_model_tier, theme, language, preferences_saved FROM user_preferences WHERE user_id=?",
                (rs, n) -> new View("PRO".equals(rs.getString(1)) ? "PRO" : "FLASH", rs.getString(2), rs.getString(3), rs.getBoolean(4)), owner);
        return rows.isEmpty() ? new View("FLASH", "system", "zh-CN", false) : rows.get(0);
    }

    public View save(String owner, Selection selection) {
        jdbc.update("INSERT INTO user_preferences(user_id, default_model_tier, theme, preferences_saved) VALUES (?,?,?,true) "
                + "ON CONFLICT(user_id) DO UPDATE SET default_model_tier=excluded.default_model_tier, theme=excluded.theme, preferences_saved=true",
                owner, selection.defaultModelTier(), selection.theme());
        return get(owner);
    }
}
