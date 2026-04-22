package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.BrowserSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("real-backend")
@Tag("agent")
class AgentRealWorkflowTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void realBackendAgentProviderProfileRepositoryTaskAndDispatchWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for real frontend agent workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "agent-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();
            JsonNode workItem = createStory(apiSession, cleanup, workspace.projectId(), "Browser agent story " + suffix);
            String providerKey = "browser_agent_" + suffix;
            String providerName = "Browser Agent Provider " + suffix;
            String profileName = "Browser Agent Profile " + suffix;
            String repositoryName = "Browser Agent Repository " + suffix;

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/agents");
            assertThat(page.locator("xpath=//h2[normalize-space()='Provider']").first()).isVisible();
            panel(page, "Agent Records").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Load")).click();

            Locator providerPanel = panel(page, "Provider");
            providerPanel.getByLabel("Key").fill(providerKey);
            providerPanel.getByLabel("Display name").fill(providerName);
            providerPanel.getByLabel("Dispatch").selectOption("managed");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create provider")).click();
            JsonNode provider = waitForRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-providers", "providerKey", providerKey);
            String providerId = provider.path("id").asText();
            cleanup.add(() -> apiSession.post("/api/v1/agent-providers/" + providerId + "/deactivate", Map.of()));

            Locator profilePanel = panel(page, "Profile");
            profilePanel.getByLabel("Provider").selectOption(providerId);
            profilePanel.getByLabel("Display name").fill(profileName);
            profilePanel.getByLabel("Username").fill("browser-agent-" + suffix);
            profilePanel.getByLabel("Project IDs").fill(workspace.projectId());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create profile")).click();
            JsonNode profile = waitForRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/agents", "displayName", profileName);
            String profileId = profile.path("id").asText();
            cleanup.add(() -> apiSession.post("/api/v1/agents/" + profileId + "/deactivate", Map.of()));

            panel(page, "Profile").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Preview runtime")).click();
            assertThat(page.getByText("trasck.agent-runtime-preview.v1").first()).isVisible();

            Locator repositoryPanel = panel(page, "Repository");
            repositoryPanel.getByLabel("Name").fill(repositoryName);
            repositoryPanel.getByLabel("URL").fill("https://example.test/" + providerKey + ".git");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
            JsonNode repository = waitForRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/repository-connections", "name", repositoryName);
            String repositoryId = repository.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/repository-connections/" + repositoryId);

            Locator taskPanel = panel(page, "Agent Task");
            taskPanel.locator("select").nth(0).selectOption(workItem.path("id").asText());
            taskPanel.locator("select").nth(1).selectOption(profileId);
            taskPanel.locator("select").nth(2).selectOption(repositoryId);
            taskPanel.getByLabel("Instructions").fill("Review this story and produce an implementation plan for " + suffix + ".");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign")).click();
            assertThat(page.getByText("running").first()).isVisible();
            String taskId = latestDispatchAttempt(apiSession, workspace.workspaceId(), profileId, workItem.path("id").asText())
                    .path("agentTaskId").asText();
            assertThat(page.getByText(taskId).first()).isVisible();

            taskPanel.locator("button[title='Cancel']").click();
            assertThat(page.getByText("canceled").first()).isVisible();
            taskPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Retry")).click();
            assertThat(page.getByText("retry").first()).isVisible();
            taskPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Load")).click();
            assertThat(page.getByText(taskId).first()).isVisible();

            Locator recordsPanel = panel(page, "Agent Records");
            recordsPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Attempts").setExact(true)).click();
            assertThat(page.getByText("dispatch").first()).isVisible();
            recordsPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Export attempts").setExact(true)).click();
            assertThat(page.getByText("agent_dispatch_attempts").first()).isVisible();
            recordsPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Prune attempts").setExact(true)).click();
            assertThat(page.getByText("attemptsPruned").first()).isVisible();

            profilePanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Deactivate profile")).click();
            assertThat(page.getByText("disabled").first()).isVisible();
            providerPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Deactivate provider")).click();
            assertFalse(requireRecordByField(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-providers", "providerKey", providerKey)
                    .path("enabled")
                    .asBoolean());

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
        }
    }

    private JsonNode createStory(AuthSession session, ApiCleanup cleanup, String projectId, String title) {
        APIResponse response = session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", title,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by the real-backend Java Playwright agent browser workflow.",
                "visibility", "inherited"
        ));
        ApiDiagnostics.writeSnippet("agent-real-work-item-create", "POST project work item for agent browser workflow", response);
        JsonNode workItem = session.requireJson(response, 201);
        cleanup.delete(session, "/api/v1/work-items/" + workItem.path("id").asText());
        return workItem;
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

    private void installFrontendContext(Page page, TestWorkspace workspace) {
        String script = "localStorage.setItem('trasck.apiBaseUrl', '" + jsString(config.backendBaseUrl().toString()) + "');"
                + "localStorage.setItem('trasck.workspaceId', '" + jsString(workspace.workspaceId()) + "');"
                + "localStorage.setItem('trasck.projectId', '" + jsString(workspace.projectId()) + "');";
        page.addInitScript(script);
    }

    private Locator panel(Page page, String title) {
        return page.locator("xpath=//section[contains(concat(' ', normalize-space(@class), ' '), ' panel ')][.//h2[normalize-space()="
                + xpathLiteral(title) + "]]").first();
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
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return requireRecordByField(session, path, field, value);
    }

    private JsonNode latestDispatchAttempt(AuthSession session, String workspaceId, String profileId, String workItemId) {
        JsonNode attempts = session.requireJson(session.get("/api/v1/workspaces/" + workspaceId
                + "/agent-dispatch-attempts?agentProfileId=" + profileId
                + "&workItemId=" + workItemId
                + "&limit=10"), 200);
        JsonNode items = attempts.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new AssertionError("Agent dispatch attempt not found: " + attempts);
        }
        return items.get(0);
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
