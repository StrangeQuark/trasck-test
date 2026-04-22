package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.BrowserSession;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("real-backend")
@Tag("notification-workflow")
class NotificationPreferenceRealWorkflowTest extends RealFrontendWorkflowSupport {
    @Test
    void realBackendNotificationPreferenceWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for notification real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "notification-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/automation");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Notification Preferences"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();

            String eventType = "browser.notification." + suffix;
            Locator preferencePanel = panel(page, "Notification Preferences");
            preferencePanel.getByLabel("Event type").fill(eventType);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save preference")).click();
            JsonNode preference = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/notification-preferences",
                    "eventType",
                    eventType);
            cleanup.delete(apiSession, "/api/v1/notification-preferences/" + preference.path("id").asText());
            assertThat(page.getByText(eventType).first()).isVisible();

            String defaultEventType = "browser.default." + suffix;
            preferencePanel.getByLabel("Default event").fill(defaultEventType);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save default")).click();
            JsonNode defaultPreference = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/notification-defaults",
                    "eventType",
                    defaultEventType);
            cleanup.delete(apiSession, "/api/v1/notification-defaults/" + defaultPreference.path("id").asText());
            assertThat(page.getByText(defaultEventType).first()).isVisible();

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
        }
    }
}
