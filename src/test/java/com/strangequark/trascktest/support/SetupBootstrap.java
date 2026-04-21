package com.strangequark.trascktest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.strangequark.trascktest.config.TrasckTestConfig;
import java.util.Map;

public final class SetupBootstrap {
    private static BootstrapContext cachedContext;
    private static BootstrapResult cachedResult;

    private SetupBootstrap() {
    }

    public static synchronized BootstrapContext require(Playwright playwright, TrasckTestConfig config) {
        if (config.hasLoginCredentials() && config.hasWorkspaceContext()) {
            return BootstrapContext.fromConfig(config);
        }
        assumeTrue(config.allowSetupBootstrap(),
                "Set TRASCK_E2E_LOGIN_IDENTIFIER/TRASCK_E2E_LOGIN_PASSWORD/TRASCK_E2E_WORKSPACE_ID/TRASCK_E2E_PROJECT_ID, or set TRASCK_E2E_ALLOW_SETUP=true against an empty disposable stack");

        BootstrapResult result = tryBootstrap(playwright, config);
        assumeTrue(result.context() != null,
                "Setup has already completed and no explicit workspace/project/login env values were provided; reset the local stack or set the TRASCK_E2E_* IDs");
        return result.context();
    }

    public static synchronized BootstrapResult tryBootstrap(Playwright playwright, TrasckTestConfig config) {
        if (cachedResult != null) {
            return cachedResult;
        }
        if (config.hasLoginCredentials() && config.hasWorkspaceContext()) {
            cachedContext = BootstrapContext.fromConfig(config);
            cachedResult = new BootstrapResult(BootstrapStatus.CONFIGURED, cachedContext);
            return cachedResult;
        }
        assumeTrue(config.allowSetupBootstrap(),
                "Set TRASCK_E2E_ALLOW_SETUP=true to let the Java Playwright suite create a disposable first-run stack through /api/v1/setup");

        String suffix = UniqueData.suffix();
        String username = "pw-" + suffix;
        String password = "correct-horse-battery-staple";
        APIRequestContext request = ApiRequestFactory.backend(playwright, config);
        try {
            APIResponse response = request.post("/api/v1/setup", RequestOptions.create().setData(setupBody(suffix, username, password)));
            ApiDiagnostics.writeSnippet("setup-bootstrap", "POST /api/v1/setup", response);
            if (response.status() == 201) {
                JsonNode body = JsonSupport.read(response.text());
                assertEquals("human", body.at("/adminUser/accountType").asText(), body.toString());
                cachedContext = new BootstrapContext(
                        username,
                        password,
                        body.at("/workspace/id").asText(),
                        body.at("/project/id").asText()
                );
                cachedResult = new BootstrapResult(BootstrapStatus.CREATED, cachedContext);
                return cachedResult;
            }
            if (response.status() == 409) {
                cachedResult = new BootstrapResult(BootstrapStatus.ALREADY_COMPLETED, null);
                return cachedResult;
            }
            throw new AssertionError("Expected setup bootstrap to return 201 or 409, got " + response.status() + ": " + response.text());
        } finally {
            request.dispose();
        }
    }

    public static Map<String, Object> setupBody(String suffix, String username, String password) {
        String keySuffix = suffix.substring(0, Math.min(6, suffix.length())).toUpperCase();
        return JsonSupport.object(
                "adminUser", JsonSupport.object(
                        "email", username + "@example.test",
                        "username", username,
                        "displayName", "Playwright Setup Admin",
                        "password", password
                ),
                "organization", JsonSupport.object(
                        "name", "Playwright Organization " + suffix,
                        "slug", "playwright-organization-" + suffix
                ),
                "workspace", JsonSupport.object(
                        "name", "Playwright Workspace " + suffix,
                        "key", "PW" + keySuffix,
                        "timezone", "America/Chicago",
                        "locale", "en-US",
                        "anonymousReadEnabled", true
                ),
                "project", JsonSupport.object(
                        "name", "Playwright Project " + suffix,
                        "key", "P" + keySuffix,
                        "description", "Disposable project created by the external Java Playwright suite",
                        "visibility", "public"
                )
        );
    }

    public enum BootstrapStatus {
        CONFIGURED,
        CREATED,
        ALREADY_COMPLETED
    }

    public record BootstrapResult(BootstrapStatus status, BootstrapContext context) {
    }

    public record BootstrapContext(
            String loginIdentifier,
            String loginPassword,
            String workspaceId,
            String projectId
    ) {
        static BootstrapContext fromConfig(TrasckTestConfig config) {
            return new BootstrapContext(
                    config.loginIdentifier(),
                    config.loginPassword(),
                    config.workspaceId(),
                    config.projectId()
            );
        }
    }
}
