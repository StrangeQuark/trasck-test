package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
class HealthApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void backendHealthEndpointResponds() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            APIRequestContext request = playwright.request().newContext(new APIRequest.NewContextOptions()
                    .setBaseURL(config.backendBaseUrl().toString()));
            APIResponse response = request.get("/api/trasck/health");

            assertEquals(200, response.status(), response.text());
            assertEquals("200 OK", response.text());
            request.dispose();
        }
    }
}
