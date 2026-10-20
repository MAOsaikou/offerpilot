package com.offerpilot.github;

import com.offerpilot.config.OfferPilotProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubTokenCipherTest {
    GitHubTokenCipher cipher(String key) {
        var properties = mock(OfferPilotProperties.class);
        when(properties.encryption()).thenReturn(new OfferPilotProperties.Encryption(key));
        return new GitHubTokenCipher(properties);
    }
    @Test void encryptionIsRandomizedAuthenticatedAndBoundToOwner() {
        var cipher = cipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        var stored = cipher.encrypt("owner-a","fictional_only");
        assertThat(stored).doesNotContain("fictional_only").isNotEqualTo(cipher.encrypt("owner-a","fictional_only"));
        assertThat(cipher.decrypt("owner-a",stored)).isEqualTo("fictional_only");
        assertThatThrownBy(() -> cipher.decrypt("owner-b",stored)).hasMessageContaining("凭据加解密不可用");
        assertThatThrownBy(() -> cipher.decrypt("owner-a", stored.substring(0,stored.length()-5)+"AAAA=")).hasMessageContaining("凭据加解密不可用");
    }
    @Test void invalidOrMissingDeploymentKeysFailClosedWithoutDisclosure() {
        for (String key : new String[]{null, "", "change-me", "YQ=="})
            assertThatThrownBy(() -> cipher(key).encrypt("owner","fictional_only")).hasMessageContaining("DATA_ENCRYPTION_KEY").hasMessageNotContaining("fictional_only");
    }
}
