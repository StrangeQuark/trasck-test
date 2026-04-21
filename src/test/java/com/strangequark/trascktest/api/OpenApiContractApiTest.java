package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.RuntimeChecks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("contract")
@Tag("smoke")
class OpenApiContractApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void openApiContractIsAvailableInLocalProfiles() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);
            APIResponse response = request.get("/v3/api-docs");
            ApiDiagnostics.writeSnippet("openapi-contract", "GET /v3/api-docs", response);

            assertEquals(200, response.status(), response.text());
            assertTrue(response.text().contains("\"openapi\""), response.text());
            request.dispose();
        }
    }
}
