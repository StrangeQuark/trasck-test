package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.BrowserSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("real-backend")
@Tag("dashboard-workflow")
class DashboardRealWorkflowTest extends RealFrontendWorkflowSupport {
    @Test
    void realBackendDashboardWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for dashboard real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "dashboard-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/dashboards");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Dashboard Builder"))).isVisible();
            String dashboardName = "Browser Dashboard " + suffix;
            JsonNode dashboard = apiSession.requireJson(apiSession.post(
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/dashboards",
                    JsonSupport.object(
                            "name", dashboardName,
                            "visibility", "project",
                            "projectId", workspace.projectId(),
                            "layout", JsonSupport.object("columns", 12)
                    )
            ), 201);
            String dashboardId = dashboard.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/dashboards/" + dashboardId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            panel(page, "Widget").locator("select").first().selectOption(dashboardId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add widget")).click();
            assertThat(page.getByText("widgets").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load import reports")).click();
            assertThat(page.getByText("completedJobs").first()).isVisible();

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
        }
    }
}
