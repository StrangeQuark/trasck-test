package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.BrowserSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SampleDataFixture;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import java.util.Map;
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
                assertThat(page.locator("xpath=//h2[normalize-space()='Teams']").first()).isVisible();
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();

                page.navigate("/configuration");
                assertThat(page.locator("xpath=//h2[normalize-space()='Custom Field']").first()).isVisible();
                page.navigate("/imports");
                assertThat(page.locator("xpath=//h2[normalize-space()='Import Job']").first()).isVisible();
                page.navigate("/automation");
                assertThat(page.locator("xpath=//h2[normalize-space()='Webhooks']").first()).isVisible();
                page.navigate("/agents");
                assertThat(page.locator("xpath=//h2[normalize-space()='Provider']").first()).isVisible();

                browserSession.screenshot();
                browserSession.assertNoConsoleErrors();
            }
        }
    }

    @Test
    void disposableFirstRunLongScenarioCreatesPlanningImportAutomationAndAgentRecords() {
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
                    BrowserSession browserSession = BrowserSession.start(browser, config, "frontend-disposable-first-run-long-scenario")) {
                TestWorkspace workspace = new TestWorkspace(
                        bootstrap.context().workspaceId(),
                        bootstrap.context().projectId()
                );
                SampleDataFixture.SampleDataContext seed = SampleDataFixture.create(apiSession, workspace, cleanup);
                String suffix = UniqueData.suffix();
                Page page = browserSession.page();
                installFrontendContext(page, workspace, seed);
                loginThroughUi(page, bootstrap.context());

                page.navigate("/planning");
                assertThat(page.locator("xpath=//h2[normalize-space()='Teams']").first()).isVisible();
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
                String teamName = "First Run Rehearsal Team " + suffix;
                panel(page, "Teams").getByLabel("Team name").fill(teamName);
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create team")).click();
                JsonNode team = waitForRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/teams", "name", teamName);
                cleanup.delete(apiSession, "/api/v1/teams/" + team.path("id").asText());

                page.navigate("/imports");
                assertThat(page.locator("xpath=//h2[normalize-space()='Import Job']").first()).isVisible();
                String importScenario = "first-run-import-" + suffix;
                panel(page, "Import Job").getByLabel("Scenario").fill(importScenario);
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create job")).click();
                JsonNode importJob = waitForImportJobByScenario(apiSession, workspace.workspaceId(), importScenario);
                cleanup.add(() -> apiSession.post("/api/v1/import-jobs/" + importJob.path("id").asText() + "/cancel", Map.of()));
                panel(page, "Parse").getByLabel("Source type").selectOption("row");
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Parse")).click();
                assertThat(page.getByText("recordsParsed").first()).isVisible();

                page.navigate("/automation");
                assertThat(page.locator("xpath=//h2[normalize-space()='Webhooks']").first()).isVisible();
                String webhookName = "First Run Webhook " + suffix;
                panel(page, "Webhooks").getByLabel("Name").fill(webhookName);
                panel(page, "Webhooks").getByLabel("URL").fill("https://example.test/hooks/" + suffix);
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create webhook")).click();
                JsonNode webhook = waitForRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/webhooks", "name", webhookName);
                cleanup.delete(apiSession, "/api/v1/webhooks/" + webhook.path("id").asText());

                String ruleName = "First Run Rule " + suffix;
                panel(page, "Automation Rule").getByLabel("Name").fill(ruleName);
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create rule")).click();
                JsonNode rule = waitForRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/automation-rules", "name", ruleName);
                cleanup.delete(apiSession, "/api/v1/automation-rules/" + rule.path("id").asText());

                page.navigate("/agents");
                assertThat(page.locator("xpath=//h2[normalize-space()='Provider']").first()).isVisible();
                panel(page, "Agent Records").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Load")).click();
                String providerKey = "first_run_agent_" + suffix;
                String providerName = "First Run Agent Provider " + suffix;
                Locator providerPanel = panel(page, "Provider");
                providerPanel.getByLabel("Key").fill(providerKey);
                providerPanel.getByLabel("Display name").fill(providerName);
                providerPanel.getByLabel("Dispatch").selectOption("managed");
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create provider")).click();
                JsonNode provider = waitForRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-providers", "providerKey", providerKey);
                cleanup.add(() -> apiSession.post("/api/v1/agent-providers/" + provider.path("id").asText() + "/deactivate", Map.of()));

                String profileName = "First Run Agent Profile " + suffix;
                Locator profilePanel = panel(page, "Profile");
                profilePanel.getByLabel("Provider").selectOption(provider.path("id").asText());
                profilePanel.getByLabel("Display name").fill(profileName);
                profilePanel.getByLabel("Username").fill("first-run-agent-" + suffix);
                profilePanel.getByLabel("Project access").selectOption("current_project");
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create profile")).click();
                JsonNode profile = waitForRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/agents", "displayName", profileName);
                cleanup.add(() -> apiSession.post("/api/v1/agents/" + profile.path("id").asText() + "/deactivate", Map.of()));

                String repositoryName = "First Run Repository " + suffix;
                Locator repositoryPanel = panel(page, "Repository");
                repositoryPanel.getByLabel("Name").fill(repositoryName);
                repositoryPanel.getByLabel("URL").fill("https://example.test/first-run/" + suffix + ".git");
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
                JsonNode repository = waitForRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/repository-connections", "name", repositoryName);
                cleanup.delete(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/repository-connections/" + repository.path("id").asText());

                Locator taskPanel = panel(page, "Agent Task");
                taskPanel.locator("select").nth(0).selectOption(seed.workItem().path("id").asText());
                taskPanel.locator("select").nth(1).selectOption(profile.path("id").asText());
                taskPanel.locator("select").nth(2).selectOption(repository.path("id").asText());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign")).click();
                assertThat(page.getByText("running").first()).isVisible();
                taskPanel.locator("button[title='Cancel']").click();
                assertThat(page.getByText("canceled").first()).isVisible();

                browserSession.screenshot();
                browserSession.assertNoConsoleErrors();
            }
        }
    }

    private void loginThroughUi(Page page, SetupBootstrap.BootstrapContext login) {
        page.navigate("/auth");
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign In"))).isVisible();
        page.getByLabel("Identifier").fill(login.loginIdentifier());
        page.getByLabel("Password").fill(login.loginPassword());
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
        assertThat(page.getByText("human").first()).isVisible();
        assertNull(page.evaluate("() => localStorage.getItem('trasck.accessToken')"));
    }

    private void installFrontendContext(Page page, TestWorkspace workspace, SampleDataFixture.SampleDataContext seed) {
        String script = "localStorage.setItem('trasck.apiBaseUrl', '" + jsString(config.backendBaseUrl().toString()) + "');"
                + "localStorage.setItem('trasck.workspaceId', '" + jsString(workspace.workspaceId()) + "');"
                + "localStorage.setItem('trasck.projectId', '" + jsString(workspace.projectId()) + "');"
                + "localStorage.setItem('trasck.savedFilterId', '" + jsString(seed.savedFilter().path("id").asText()) + "');"
                + "localStorage.setItem('trasck.dashboardId', '" + jsString(seed.dashboard().path("id").asText()) + "');";
        page.addInitScript(script);
    }

    private Locator panel(Page page, String title) {
        return page.locator("xpath=//section[contains(concat(' ', normalize-space(@class), ' '), ' panel ')][.//h2[normalize-space()="
                + xpathLiteral(title) + "]]").first();
    }

    private JsonNode requireImportJobByScenario(AuthSession session, String workspaceId, String scenario) {
        JsonNode rows = session.requireJson(session.get("/api/v1/workspaces/" + workspaceId + "/import-jobs"), 200);
        for (JsonNode row : rows) {
            if (scenario.equals(row.path("config").path("scenario").asText())) {
                return row;
            }
        }
        throw new AssertionError("Could not find import job for scenario " + scenario + ": " + rows);
    }

    private JsonNode waitForImportJobByScenario(AuthSession session, String workspaceId, String scenario) {
        long deadline = System.currentTimeMillis() + 5_000;
        AssertionError lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return requireImportJobByScenario(session, workspaceId, scenario);
            } catch (AssertionError ex) {
                lastFailure = ex;
                sleepWhileWaiting("import job " + scenario, ex);
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return requireImportJobByScenario(session, workspaceId, scenario);
    }

    private JsonNode requireRecordByField(AuthSession session, String path, String field, String value) {
        JsonNode rows = session.requireJson(session.get(path), 200);
        for (JsonNode row : rows) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("Could not find record with " + field + "=" + value + " from " + path + ": " + rows);
    }

    private JsonNode waitForRecordByField(AuthSession session, String path, String field, String value) {
        long deadline = System.currentTimeMillis() + 5_000;
        AssertionError lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return requireRecordByField(session, path, field, value);
            } catch (AssertionError ex) {
                lastFailure = ex;
                sleepWhileWaiting(field + "=" + value, ex);
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return requireRecordByField(session, path, field, value);
    }

    private void sleepWhileWaiting(String label, AssertionError failure) {
        try {
            Thread.sleep(150);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for " + label, failure);
        }
    }

    private String jsString(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String xpathLiteral(String value) {
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\"", "") + "\"";
    }
}
