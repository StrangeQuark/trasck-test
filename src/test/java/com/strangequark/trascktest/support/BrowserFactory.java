package com.strangequark.trascktest.support;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;

public final class BrowserFactory {
    private BrowserFactory() {
    }

    public static Browser launch(Playwright playwright, TrasckTestConfig config) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(config.headless())
                .setTimeout(config.timeout().toMillis());
        return switch (config.browserName()) {
            case "firefox" -> playwright.firefox().launch(options);
            case "webkit" -> playwright.webkit().launch(options);
            case "chromium" -> playwright.chromium().launch(options);
            default -> throw new IllegalArgumentException("Unsupported TRASCK_E2E_BROWSER: " + config.browserName());
        };
    }
}
