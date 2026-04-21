package com.strangequark.trascktest.config;

import com.strangequark.utility.EnvUtility;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;

public record TrasckTestConfig(
        URI backendBaseUrl,
        URI frontendBaseUrl,
        String browserName,
        boolean headless,
        Duration timeout,
        String loginIdentifier,
        String loginPassword
) {
    public static TrasckTestConfig load() {
        return new TrasckTestConfig(
                normalizedUri(EnvUtility.getEnvVar("TRASCK_BACKEND_BASE_URL", "http://localhost:6100")),
                normalizedUri(EnvUtility.getEnvVar("TRASCK_FRONTEND_BASE_URL", "http://localhost:8080")),
                EnvUtility.getEnvVar("TRASCK_E2E_BROWSER", "chromium").trim().toLowerCase(Locale.ROOT),
                Boolean.parseBoolean(EnvUtility.getEnvVar("TRASCK_E2E_HEADLESS", "true")),
                Duration.ofMillis(Long.parseLong(EnvUtility.getEnvVar("TRASCK_E2E_TIMEOUT_MS", "30000"))),
                blankToNull(EnvUtility.getEnvVar("TRASCK_E2E_LOGIN_IDENTIFIER", "")),
                blankToNull(EnvUtility.getEnvVar("TRASCK_E2E_LOGIN_PASSWORD", ""))
        );
    }

    public boolean hasLoginCredentials() {
        return loginIdentifier != null && loginPassword != null;
    }

    private static URI normalizedUri(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Base URL must not be blank");
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return URI.create(trimmed);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
