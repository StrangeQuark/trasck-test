package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.UniqueData;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("smoke")
class SetupReadinessApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void setupEndpointIsReachableWithoutAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);
            APIResponse response = request.post("/api/v1/setup", RequestOptions.create().setData(Map.of()));
            ApiDiagnostics.writeSnippet("setup-readiness", "POST /api/v1/setup {}", response);

            assertTrue(response.status() == 400 || response.status() == 409, response.text());
            request.dispose();
        }
    }

    @Test
    void setupBootstrapModeCanCreateAStackOnceAndThenReturnsConflict() {
        assumeTrue(config.allowSetupBootstrap(),
                "Set TRASCK_E2E_ALLOW_SETUP=true against an empty disposable stack to run setup bootstrap coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            SetupBootstrap.BootstrapResult bootstrap = SetupBootstrap.tryBootstrap(playwright, config);
            assertTrue(bootstrap.status() == SetupBootstrap.BootstrapStatus.CREATED
                    || bootstrap.status() == SetupBootstrap.BootstrapStatus.ALREADY_COMPLETED
                    || bootstrap.status() == SetupBootstrap.BootstrapStatus.CONFIGURED);

            APIRequestContext request = ApiRequestFactory.backend(playwright, config);
            String suffix = UniqueData.suffix();
            APIResponse secondSetup = request.post("/api/v1/setup", RequestOptions.create().setData(
                    SetupBootstrap.setupBody(suffix, "pw-" + suffix, "correct-horse-battery-staple")
            ));
            ApiDiagnostics.writeSnippet("setup-second-attempt", "POST /api/v1/setup after bootstrap", secondSetup);
            assertTrue(secondSetup.status() == 409, secondSetup.text());
            request.dispose();
        }
    }
}
