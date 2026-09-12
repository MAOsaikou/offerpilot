package com.offerpilot.common.id;

import com.github.f4b6a3.ulid.UlidCreator;

public final class IdGenerator {

    private IdGenerator() {
    }

    public static String next(String prefix) {
        return prefix + "_" + UlidCreator.getUlid().toLowerCase();
    }
}
