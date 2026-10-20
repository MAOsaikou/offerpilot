package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class Sc01FixtureSealTest {
    @Test void sealedV5BytesAndRowCountsRemainUnchanged() throws Exception { verifyV5Seal(); }

    static void verifyV5Seal() throws Exception {
        try (var in = Objects.requireNonNull(Sc01FixtureSealTest.class.getResourceAsStream("/redteam/heldout-v5-seal.json"))) {
            var files = new ObjectMapper().readTree(in).path("files");
            assertThat(files.size()).isEqualTo(2);
            for (String kind : java.util.List.of("attacks", "benign")) {
                String filename = "heldout-" + kind + "-v5.txt";
                try (var fixture = Objects.requireNonNull(Sc01FixtureSealTest.class.getResourceAsStream("/redteam/" + filename))) {
                    byte[] bytes = fixture.readAllBytes();
                    assertThat(fingerprint(bytes)).isEqualTo(files.path(filename).path("sha256").asText());
                    assertThat(new String(bytes, StandardCharsets.UTF_8).lines().filter(s -> !s.isBlank()).count()).isEqualTo(40);
                }
            }
        }
    }

    static String fingerprint(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
