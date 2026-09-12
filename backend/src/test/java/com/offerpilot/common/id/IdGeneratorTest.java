package com.offerpilot.common.id;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IdGeneratorTest {

    @Test
    void nextPrefixedUlid() {
        String id = IdGenerator.next("run");
        assertTrue(id.startsWith("run_"));
        assertTrue(id.length() > 10);
    }
}
