package com.strangequark.trascktest.support;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;
import java.net.URI;
import java.util.Map;

public final class ApiRequestFactory {
    private ApiRequestFactory() {
    }

    public static APIRequestContext backend(Playwright playwright, TrasckTestConfig config) {
        return baseUrl(playwright, config.backendBaseUrl());
    }

    public static APIRequestContext baseUrl(Playwright playwright, URI baseUrl) {
        return playwright.request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL(baseUrl.toString()));
    }

    public static APIRequestContext backendWithBearer(Playwright playwright, TrasckTestConfig config, String token) {
        return baseUrlWithBearer(playwright, config.backendBaseUrl(), token);
    }

    public static APIRequestContext baseUrlWithBearer(Playwright playwright, URI baseUrl, String token) {
        return playwright.request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL(baseUrl.toString())
                .setExtraHTTPHeaders(Map.of(
                        "Accept", "application/json",
                        "Authorization", "Bearer " + token
                )));
    }
}
