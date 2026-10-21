package com.offerpilot.auth.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * AC-03 account preferences that follow the user across browsers: default chat model tier, theme, language and PII masking.
 * {@code saved} is false until the user saves once, so a browser keeps its local theme instead of being
 * overwritten by the table default. The separate privacy endpoint updates the same PII column; omitted fields preserve existing values.
 */
@Service
public class UserPreferences implements com.offerpilot.llm.ChatLanguagePreference {
    public record Selection(@NotNull @Pattern(regexp = "FLASH|PRO") String defaultModelTier,
                            @NotNull @Pattern(regexp = "light|dark|system") String theme,
                            @Pattern(regexp = "zh-CN|en-US") String language,
                            Boolean piiMasking) {
        public Selection(String defaultModelTier, String theme) { this(defaultModelTier, theme, null, null); }
    }
    public record View(String defaultModelTier, String theme, String language, boolean saved, boolean piiMasking) {}
    private final JdbcTemplate jdbc;

    public UserPreferences(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public View get(String owner) {
        var rows = jdbc.query("SELECT default_model_tier, theme, language, preferences_saved, pii_masking FROM user_preferences WHERE user_id=?",
                (rs, n) -> new View("PRO".equals(rs.getString(1)) ? "PRO" : "FLASH", rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5)), owner);
        return rows.isEmpty() ? new View("FLASH", "system", "zh-CN", false, true) : rows.get(0);
    }

    public View save(String owner, Selection selection) {
        jdbc.update("INSERT INTO user_preferences(user_id, default_model_tier, theme, language, pii_masking, preferences_saved) "
                + "VALUES (?,?,?,coalesce(?,'zh-CN'),coalesce(?,true),true) "
                + "ON CONFLICT(user_id) DO UPDATE SET default_model_tier=excluded.default_model_tier, theme=excluded.theme, "
                + "language=coalesce(?,user_preferences.language), pii_masking=coalesce(?,user_preferences.pii_masking), preferences_saved=true",
                owner, selection.defaultModelTier(), selection.theme(), selection.language(), selection.piiMasking(),
                selection.language(), selection.piiMasking());
        return get(owner);
    }
    /** Read for each new turn; never cache an account's language across requests. */
    @Override public String language(String owner) { return get(owner).language(); }
}
