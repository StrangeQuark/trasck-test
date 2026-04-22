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
@Tag("configuration-workflow")
class ConfigurationSearchRealWorkflowTest extends RealFrontendWorkflowSupport {
    @Test
    void realBackendConfigurationAndSearchWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for configuration real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "configuration-search-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();
            JsonNode workItem = createStory(apiSession, cleanup, workspace.projectId(), "Browser reporting story " + suffix);

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/configuration");
            assertThat(page.locator("xpath=//h2[normalize-space()='Custom Field']").first()).isVisible();
            String customFieldName = "Browser Config Field " + suffix;
            String customFieldKey = "browser_config_" + suffix;
            Locator fieldPanel = panel(page, "Custom Field");
            fieldPanel.getByLabel("Name").fill(customFieldName);
            fieldPanel.getByLabel("Key").fill(customFieldKey);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create field")).click();
            JsonNode customField = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/custom-fields",
                    "key",
                    customFieldKey);
            String customFieldId = customField.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/custom-fields/" + customFieldId);

            Locator contextPanel = panel(page, "Context");
            contextPanel.getByLabel("Custom field").selectOption(customFieldId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add context")).click();
            JsonNode customFieldContext = waitForRecordByField(apiSession,
                    "/api/v1/custom-fields/" + customFieldId + "/contexts",
                    "projectId",
                    workspace.projectId());
            cleanup.delete(apiSession, "/api/v1/custom-fields/" + customFieldId + "/contexts/" + customFieldContext.path("id").asText());

            Locator configPanel = panel(page, "Field Configuration");
            configPanel.getByLabel("Custom field").selectOption(customFieldId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create config")).click();
            JsonNode fieldConfiguration = waitForRecordByField(apiSession,
                    "/api/v1/custom-fields/" + customFieldId + "/field-configurations",
                    "projectId",
                    workspace.projectId());
            cleanup.delete(apiSession, "/api/v1/field-configurations/" + fieldConfiguration.path("id").asText());

            String screenName = "Browser Config Screen " + suffix;
            Locator screenPanel = panel(page, "Screen");
            screenPanel.getByLabel("Name").fill(screenName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create screen")).click();
            JsonNode screen = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/screens",
                    "name",
                    screenName);
            String screenId = screen.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/screens/" + screenId);

            Locator layoutPanel = panel(page, "Screen Layout");
            layoutPanel.getByLabel("Screen").first().selectOption(screenId);
            layoutPanel.getByLabel("Custom field").selectOption(customFieldId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add field")).click();
            JsonNode screenField = waitForRecordByField(apiSession,
                    "/api/v1/screens/" + screenId + "/fields",
                    "customFieldId",
                    customFieldId);
            cleanup.delete(apiSession, "/api/v1/screens/" + screenId + "/fields/" + screenField.path("id").asText());

            layoutPanel.getByLabel("Screen").last().selectOption(screenId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign screen")).click();
            JsonNode screenAssignment = waitForRecordByField(apiSession,
                    "/api/v1/screens/" + screenId + "/assignments",
                    "projectId",
                    workspace.projectId());
            cleanup.delete(apiSession, "/api/v1/screens/" + screenId + "/assignments/" + screenAssignment.path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Screen details")).click();
            assertThat(page.getByText("Assignments").first()).isVisible();

            page.navigate("/filters");
            assertThat(page.locator("xpath=//h2[normalize-space()='Saved Filter Builder']").first()).isVisible();
            String filterName = "Browser Saved Filter " + suffix;
            Locator filterPanel = panel(page, "Saved Filter Builder");
            filterPanel.getByLabel("Name").fill(filterName);
            filterPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Sync")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create filter")).click();
            JsonNode savedFilter = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/saved-filters",
                    "name",
                    filterName);
            String savedFilterId = savedFilter.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/saved-filters/" + savedFilterId);
            panel(page, "Execute").getByLabel("Saved filter").selectOption(savedFilterId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run")).click();
            assertThat(page.getByText(workItem.path("title").asText()).first()).isVisible();

            String viewName = "Browser Saved View " + suffix;
            panel(page, "Saved View").getByLabel("Name").fill(viewName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create view")).click();
            JsonNode savedView = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/views",
                    "name",
                    viewName);
            cleanup.delete(apiSession, "/api/v1/personalization/views/" + savedView.path("id").asText());

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
        }
    }
}
