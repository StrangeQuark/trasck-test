package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.RuntimeChecks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("smoke")
class FrontendShellTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void frontendShellLoadsCoreNavigation() {
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setBaseURL(config.frontendBaseUrl().toString()))) {
            Page page = context.newPage();
            page.navigate("/");

            assertThat(page.locator(".app-kicker")).hasText("Trasck");
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Setup"))).isVisible();
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Auth"))).isVisible();
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Work"))).isVisible();
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("System"))).isVisible();
        }
    }
}
