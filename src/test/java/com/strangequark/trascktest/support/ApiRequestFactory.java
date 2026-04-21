package com.strangequark.trascktest.support;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;

public final class ApiRequestFactory {
    private ApiRequestFactory() {
    }

    public static APIRequestContext backend(Playwright playwright, TrasckTestConfig config) {
        return playwright.request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL(config.backendBaseUrl().toString()));
    }
}
