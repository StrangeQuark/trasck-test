package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.RuntimeChecks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("smoke")
class CsrfApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void csrfEndpointReturnsBrowserTokenMetadata() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            APIRequestContext request = playwright.request().newContext(new APIRequest.NewContextOptions()
                    .setBaseURL(config.backendBaseUrl().toString()));
            APIResponse response = request.get("/api/v1/auth/csrf");
            String body = response.text();

            assertEquals(200, response.status(), body);
            assertFalse(body.isBlank());
            assertTrue(body.contains("\"headerName\""), body);
            assertTrue(body.contains("\"parameterName\""), body);
            assertTrue(body.contains("\"token\""), body);
            assertFalse(response.headers().getOrDefault("content-type", "").isBlank());
            request.dispose();
        }
    }
}
