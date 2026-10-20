package com.offerpilot.github;

import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.common.error.*;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Component
public class GitHubTokenCipher {
    private final String configuredKey;
    public GitHubTokenCipher(OfferPilotProperties properties) {
        configuredKey = properties.encryption() == null ? null : properties.encryption().key();
    }
    public String encrypt(String owner, String token) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            var cipher = cipher(Cipher.ENCRYPT_MODE, owner, iv);
            return "v1:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw failure(); }
    }
    public String decrypt(String owner, String stored) {
        try {
            var parts = stored.split(":");
            if (parts.length != 3 || !"v1".equals(parts[0])) throw failure();
            return new String(cipher(Cipher.DECRYPT_MODE, owner, Base64.getDecoder().decode(parts[1]))
                    .doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw failure(); }
    }
    private Cipher cipher(int mode, String owner, byte[] iv) throws Exception {
        byte[] key = Base64.getDecoder().decode(configuredKey);
        if (key.length != 32 || iv.length != 12) throw failure();
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    private BusinessException failure() { return new BusinessException(ErrorCode.VALIDATION_FAILED,
            "GitHub 凭据加解密不可用，请管理员配置有效的 DATA_ENCRYPTION_KEY（Base64 32字节），或移除 Token 使用公开用户名"); }
}
