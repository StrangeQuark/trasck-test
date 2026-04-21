package com.strangequark.trascktest.support;

import java.util.UUID;

public final class UniqueData {
    private UniqueData() {
    }

    public static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String email(String prefix) {
        return prefix + "-" + suffix() + "@example.test";
    }
}
