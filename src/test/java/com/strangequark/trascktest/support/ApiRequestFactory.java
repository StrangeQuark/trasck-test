package com.strangequark.trascktest.support;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;
import java.util.Map;

public final class ApiRequestFactory {
    private ApiRequestFactory() {
    }

    public static APIRequestContext backend(Playwright playwright, TrasckTestConfig config) {
        return playwright.request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL(config.backendBaseUrl().toString()));
    }

    public static APIRequestContext backendWithBearer(Playwright playwright, TrasckTestConfig config, String token) {
        return playwright.request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL(config.backendBaseUrl().toString())
                .setExtraHTTPHeaders(Map.of(
                        "Accept", "application/json",
                        "Authorization", "Bearer " + token
                )));
    }
}
