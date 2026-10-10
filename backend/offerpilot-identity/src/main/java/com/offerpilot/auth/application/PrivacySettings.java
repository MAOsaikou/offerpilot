package com.offerpilot.auth.application;

import com.offerpilot.llm.privacy.PiiMaskingPreference;
import com.offerpilot.llm.privacy.PiiMasker;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PrivacySettings implements PiiMaskingPreference {
    public record Selection(@NotNull Boolean piiMasking) {}
    public record Risk(boolean detected, String warning) {}
    public static final String WARNING = "检测到联系方式或身份证号。当前已关闭文本脱敏，相关信息可能发送给模型；建议在设置中开启。";
    private final JdbcTemplate jdbc;
    public PrivacySettings(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean enabled(String owner) {
        var rows = jdbc.query("SELECT pii_masking FROM user_preferences WHERE user_id=?", (rs, n) -> rs.getBoolean(1), owner);
        return rows.isEmpty() || rows.get(0);
    }
    public Selection get(String owner) { return new Selection(enabled(owner)); }
    public Selection save(String owner, Selection selection) {
        jdbc.update("INSERT INTO user_preferences(user_id,pii_masking) VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET pii_masking=excluded.pii_masking,updated_at=now()", owner, selection.piiMasking());
        return get(owner);
    }
    public Risk check(String owner, String text) {
        boolean detected = PiiMasker.detects(text);
        return new Risk(detected, detected && !enabled(owner) ? WARNING : null);
    }
}
