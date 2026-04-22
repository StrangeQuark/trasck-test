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
@Tag("automation-workflow")
class AutomationWorkerRealWorkflowTest extends RealFrontendWorkflowSupport {
    @Test
    void realBackendAutomationWorkerWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for automation real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "automation-worker-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();
            JsonNode sourceWorkItem = createStory(apiSession, cleanup, workspace.projectId(), "Browser automation source " + suffix);

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/automation");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Notification Preferences"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();

            String webhookName = "Browser Automation Webhook " + suffix;
            Locator webhookPanel = panel(page, "Webhooks");
            webhookPanel.getByLabel("Name").fill(webhookName);
            webhookPanel.getByLabel("URL").fill("https://example.test/hooks/" + suffix);
            webhookPanel.getByLabel("Secret", new Locator.GetByLabelOptions().setExact(true)).fill("secret-" + suffix);
            webhookPanel.getByLabel("Secret overlap seconds").fill("60");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create webhook")).click();
            JsonNode webhook = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/webhooks",
                    "name",
                    webhookName);
            String webhookId = webhook.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/webhooks/" + webhookId);

            String ruleName = "Browser Automation Rule " + suffix;
            Locator rulePanel = panel(page, "Automation Rule");
            rulePanel.getByLabel("Name").fill(ruleName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create rule")).click();
            JsonNode rule = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/automation-rules",
                    "name",
                    ruleName);
            String ruleId = rule.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/automation-rules/" + ruleId);

            Locator conditionsPanel = panel(page, "Conditions And Actions");
            conditionsPanel.getByLabel("Rule").selectOption(ruleId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add condition")).click();
            conditionsPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Use webhook")).click();
            conditionsPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Add action")).click();

            Locator executionPanel = panel(page, "Execution");
            executionPanel.getByLabel("Rule").selectOption(ruleId);
            executionPanel.getByLabel("Work item").selectOption(sourceWorkItem.path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Queue rule")).click();
            assertThat(page.getByText("queued").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run jobs")).click();
            assertThat(page.getByText("processed").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run deliveries")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run emails")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save worker settings")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Export runs")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Prune runs")).click();
            assertThat(page.getByText("Worker Runs").first()).isVisible();

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
        }
    }
}
