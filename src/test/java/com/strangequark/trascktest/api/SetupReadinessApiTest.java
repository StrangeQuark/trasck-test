package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.RuntimeChecks;
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
}
