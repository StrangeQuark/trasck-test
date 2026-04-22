package com.strangequark.trascktest.config;

import com.strangequark.utility.EnvUtility;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

public record TrasckTestConfig(
        URI backendBaseUrl,
        URI frontendBaseUrl,
        String browserName,
        boolean headless,
        Duration timeout,
        String loginIdentifier,
        String loginPassword,
        String workspaceId,
        String projectId,
        boolean allowSetupBootstrap,
        boolean seedSampleData,
        boolean managedProductionStackEnabled,
        Path managedProductionBackendDirectory,
        Duration managedProductionStartupTimeout,
        String localReceiverBindHost,
        int localReceiverPort,
        URI localReceiverPublicBaseUrl
) {
    public static TrasckTestConfig load() {
        int localReceiverPort = Integer.parseInt(EnvUtility.getEnvVar("TRASCK_E2E_LOCAL_RECEIVER_PORT", "6199"));
        return new TrasckTestConfig(
                normalizedUri(EnvUtility.getEnvVar("TRASCK_BACKEND_BASE_URL", "http://localhost:6100")),
                normalizedUri(EnvUtility.getEnvVar("TRASCK_FRONTEND_BASE_URL", "http://localhost:8080")),
                EnvUtility.getEnvVar("TRASCK_E2E_BROWSER", "chromium").trim().toLowerCase(Locale.ROOT),
                Boolean.parseBoolean(EnvUtility.getEnvVar("TRASCK_E2E_HEADLESS", "true")),
                Duration.ofMillis(Long.parseLong(EnvUtility.getEnvVar("TRASCK_E2E_TIMEOUT_MS", "30000"))),
                blankToNull(EnvUtility.getEnvVar("TRASCK_E2E_LOGIN_IDENTIFIER", "")),
                blankToNull(EnvUtility.getEnvVar("TRASCK_E2E_LOGIN_PASSWORD", "")),
                blankToNull(EnvUtility.getEnvVar("TRASCK_E2E_WORKSPACE_ID", "")),
                blankToNull(EnvUtility.getEnvVar("TRASCK_E2E_PROJECT_ID", "")),
                Boolean.parseBoolean(EnvUtility.getEnvVar("TRASCK_E2E_ALLOW_SETUP", "false")),
                Boolean.parseBoolean(EnvUtility.getEnvVar("TRASCK_E2E_SEED_SAMPLE_DATA", "false")),
                Boolean.parseBoolean(EnvUtility.getEnvVar("TRASCK_E2E_MANAGED_PROD_STACK", "false")),
                normalizedPath(EnvUtility.getEnvVar("TRASCK_E2E_BACKEND_PROJECT_DIR", "../trasck")),
                Duration.ofMillis(Long.parseLong(EnvUtility.getEnvVar("TRASCK_E2E_MANAGED_PROD_STACK_TIMEOUT_MS", "180000"))),
                EnvUtility.getEnvVar("TRASCK_E2E_LOCAL_RECEIVER_BIND_HOST", "0.0.0.0").trim(),
                localReceiverPort,
                normalizedUri(EnvUtility.getEnvVar(
                        "TRASCK_E2E_LOCAL_RECEIVER_PUBLIC_BASE_URL",
                        "http://localhost:" + localReceiverPort
                ))
        );
    }

    public boolean hasLoginCredentials() {
        return loginIdentifier != null && loginPassword != null;
    }

    public boolean hasWorkspaceContext() {
        return workspaceId != null && projectId != null;
    }

    public boolean canResolveAuthenticatedWorkspace() {
        return (hasLoginCredentials() && hasWorkspaceContext()) || allowSetupBootstrap;
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

    private static Path normalizedPath(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        return Path.of(trimmed).toAbsolutePath().normalize();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
