package com.offerpilot.resume.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ResumeModelSettings {
    public record Selection(
            @NotBlank @Pattern(regexp = "FLASH|CHEAP|PRO|VISION_ALT") String textTier,
            @NotBlank @Pattern(regexp = "FLASH|CHEAP|PRO|VISION_ALT") String visionTier) {}
    public record Settings(List<ModelGateway.ModelOption> models, Selection selection) {}
    private final JdbcTemplate jdbc;
    private final ModelGateway gateway;
    public ResumeModelSettings(JdbcTemplate jdbc, ModelGateway gateway) { this.jdbc = jdbc; this.gateway = gateway; }

    public Selection selection(String owner) {
        var rows = jdbc.query("SELECT text_tier,vision_tier FROM resume_model_preferences WHERE user_id=?",
                (rs, n) -> new Selection(rs.getString(1), rs.getString(2)), owner);
        if (!rows.isEmpty()) return rows.get(0);
        var text = gateway.defaultTier();
        return new Selection(text.name(), gateway.supportsVision(text) ? text.name() : ModelTier.VISION_ALT.name());
    }
    public Settings get(String owner) { return new Settings(gateway.catalog(), selection(owner)); }
    public Settings save(String owner, Selection selection) {
        ModelTier text = tier(selection.textTier()), vision = tier(selection.visionTier());
        gateway.requireAvailable(text, false);
        gateway.requireAvailable(vision, true);
        jdbc.update("INSERT INTO resume_model_preferences(user_id,text_tier,vision_tier) VALUES (?,?,?) "
                + "ON CONFLICT(user_id) DO UPDATE SET text_tier=excluded.text_tier,vision_tier=excluded.vision_tier,updated_at=now()",
                owner, text.name(), vision.name());
        return get(owner);
    }
    private ModelTier tier(String value) {
        try { return ModelTier.valueOf(value); }
        catch (RuntimeException ex) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择列表中的模型"); }
    }
}
