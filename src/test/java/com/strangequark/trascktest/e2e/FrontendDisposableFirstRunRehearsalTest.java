package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.BrowserSession;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SampleDataFixture;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("real-backend")
@Tag("rehearsal")
class FrontendDisposableFirstRunRehearsalTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void disposableFirstRunStackRehearsalTouchesCoreFrontendRoutes() {
        assumeTrue(config.allowSetupBootstrap() && !config.hasLoginCredentials() && !config.hasWorkspaceContext(),
                "Set only TRASCK_E2E_ALLOW_SETUP=true against an empty disposable stack for the first-run rehearsal");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            SetupBootstrap.BootstrapResult bootstrap = SetupBootstrap.tryBootstrap(playwright, config);
            assumeTrue(bootstrap.status() == SetupBootstrap.BootstrapStatus.CREATED,
                    "The first-run rehearsal requires an empty stack so it can create its own workspace/project");

            try (AuthSession apiSession = AuthSession.login(playwright, config);
                    ApiCleanup cleanup = new ApiCleanup();
                    Browser browser = BrowserFactory.launch(playwright, config);
                    BrowserSession browserSession = BrowserSession.start(browser, config, "frontend-disposable-first-run-rehearsal")) {
                TestWorkspace workspace = new TestWorkspace(
                        bootstrap.context().workspaceId(),
                        bootstrap.context().projectId()
                );
                SampleDataFixture.SampleDataContext seed = SampleDataFixture.create(apiSession, workspace, cleanup);
                Page page = browserSession.page();
                installFrontendContext(page, workspace, seed);

                page.navigate("/auth");
                assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign In"))).isVisible();
                page.getByLabel("Identifier").fill(bootstrap.context().loginIdentifier());
                page.getByLabel("Password").fill(bootstrap.context().loginPassword());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
                assertThat(page.getByText("human").first()).isVisible();
                assertNull(page.evaluate("() => localStorage.getItem('trasck.accessToken')"));

                page.navigate("/work");
                assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Project Work"))).isVisible();
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
                assertThat(page.getByText(seed.workItem().path("title").asText()).first()).isVisible();

                page.navigate("/planning");
                assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Teams"))).isVisible();
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
                assertThat(page.getByText(seed.workItem().path("title").asText()).first()).isVisible();

                page.navigate("/configuration");
                assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Custom Field"))).isVisible();
                page.navigate("/imports");
                assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Import Job"))).isVisible();
                page.navigate("/automation");
                assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Webhooks"))).isVisible();
                page.navigate("/agents");
                assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Provider"))).isVisible();

                browserSession.screenshot();
                browserSession.assertNoConsoleErrors();
            }
        }
    }

    private void installFrontendContext(Page page, TestWorkspace workspace, SampleDataFixture.SampleDataContext seed) {
        String script = "localStorage.setItem('trasck.apiBaseUrl', '" + jsString(config.backendBaseUrl().toString()) + "');"
                + "localStorage.setItem('trasck.workspaceId', '" + jsString(workspace.workspaceId()) + "');"
                + "localStorage.setItem('trasck.projectId', '" + jsString(workspace.projectId()) + "');"
                + "localStorage.setItem('trasck.savedFilterId', '" + jsString(seed.savedFilter().path("id").asText()) + "');"
                + "localStorage.setItem('trasck.dashboardId', '" + jsString(seed.dashboard().path("id").asText()) + "');";
        page.addInitScript(script);
    }

    private String jsString(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
