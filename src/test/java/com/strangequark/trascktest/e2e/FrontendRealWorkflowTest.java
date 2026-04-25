package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.strangequark.trascktest.support.UniqueData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("real-backend")
class FrontendRealWorkflowTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void realBackendSeededWorkflowExercisesAuthWorkSearchDashboardsAndPlanning() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "frontend-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            SampleDataFixture.SampleDataContext seed = SampleDataFixture.create(apiSession, workspace, cleanup);
            String suffix = UniqueData.suffix();
            String uiWorkTitle = "Browser real workflow story " + suffix;
            String teamName = "Browser real workflow team " + suffix;
            String customFieldName = "Browser real workflow field " + suffix;
            String customFieldKey = "browser-real-" + suffix;
            cleanup.add(() -> deleteWorkItemsByTitle(apiSession, workspace.projectId(), uiWorkTitle));
            cleanup.add(() -> deleteTeamsByName(apiSession, workspace.workspaceId(), teamName));
            cleanup.add(() -> deleteCustomFieldsByKey(apiSession, workspace.workspaceId(), customFieldKey));

            Page page = browserSession.page();
            installFrontendContext(page, workspace, seed);

            page.navigate("/auth");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign In"))).isVisible();
            page.getByLabel("Identifier").fill(login.loginIdentifier());
            page.getByLabel("Password").fill(login.loginPassword());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
            assertThat(page.getByText("human").first()).isVisible();
            assertNull(page.evaluate("() => localStorage.getItem('trasck.accessToken')"));

            page.navigate("/work");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Project Work"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            assertThat(page.getByText(seed.workItem().path("title").asText()).first()).isVisible();
            page.getByLabel("Title").fill(uiWorkTitle);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create").setExact(true)).click();
            waitForWorkItemsByTitle(apiSession, workspace.projectId(), uiWorkTitle);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            assertThat(page.getByText(uiWorkTitle).first()).isVisible();

            page.navigate("/filters");
            assertThat(page.locator("xpath=//h2[normalize-space()='Saved Filter Builder']").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run").setExact(true)).click();
            assertThat(page.getByText(seed.workItem().path("title").asText()).first()).isVisible();

            page.navigate("/dashboards");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Dashboard Builder"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Render").setExact(true)).click();
            assertThat(page.getByText("widgets").first()).isVisible();

            page.navigate("/planning");
            assertThat(page.locator("xpath=//h2[normalize-space()='Sprint Flow']").first()).isVisible();
            page.navigate("/planning/admin");
            assertThat(page.locator("xpath=//h2[normalize-space()='Teams']").first()).isVisible();
            page.getByLabel("Team name").fill(teamName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create team")).click();
            waitForTeamByName(apiSession, workspace.workspaceId(), teamName);

            page.navigate("/configuration");
            assertThat(page.locator("xpath=//h2[normalize-space()='Custom Field']").first()).isVisible();
            page.getByLabel("Name").first().fill(customFieldName);
            page.getByLabel("Key").fill(customFieldKey);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create field")).click();
            waitForCustomFieldByKey(apiSession, workspace.workspaceId(), customFieldKey);

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
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

    private void deleteWorkItemsByTitle(AuthSession session, String projectId, String title) {
        JsonNode page = session.requireJson(session.get("/api/v1/projects/" + projectId + "/work-items?limit=100"), 200);
        for (JsonNode item : page.path("items")) {
            if (title.equals(item.path("title").asText())) {
                deleteIgnoringMissing(session, "/api/v1/work-items/" + item.path("id").asText());
            }
        }
    }

    private void deleteTeamsByName(AuthSession session, String workspaceId, String name) {
        JsonNode teams = session.requireJson(session.get("/api/v1/workspaces/" + workspaceId + "/teams"), 200);
        for (JsonNode team : teams) {
            if (name.equals(team.path("name").asText())) {
                deleteIgnoringMissing(session, "/api/v1/teams/" + team.path("id").asText());
            }
        }
    }

    private void deleteCustomFieldsByKey(AuthSession session, String workspaceId, String key) {
        JsonNode customFields = session.requireJson(session.get("/api/v1/workspaces/" + workspaceId + "/custom-fields"), 200);
        for (JsonNode customField : customFields) {
            if (key.equals(customField.path("key").asText())) {
                deleteIgnoringMissing(session, "/api/v1/custom-fields/" + customField.path("id").asText());
            }
        }
    }

    private void deleteIgnoringMissing(AuthSession session, String path) {
        int status = session.delete(path).status();
        if (status != 200 && status != 204 && status != 404) {
            throw new AssertionError("Cleanup DELETE " + path + " returned HTTP " + status);
        }
    }

    private void waitForWorkItemsByTitle(AuthSession session, String projectId, String title) {
        waitFor(() -> {
            JsonNode page = session.requireJson(session.get("/api/v1/projects/" + projectId + "/work-items?limit=100"), 200);
            for (JsonNode item : page.path("items")) {
                if (title.equals(item.path("title").asText())) {
                    return true;
                }
            }
            return false;
        }, "work item " + title);
    }

    private void waitForTeamByName(AuthSession session, String workspaceId, String name) {
        waitFor(() -> containsArrayRecord(session, "/api/v1/workspaces/" + workspaceId + "/teams", "name", name), "team " + name);
    }

    private void waitForCustomFieldByKey(AuthSession session, String workspaceId, String key) {
        waitFor(() -> containsArrayRecord(session, "/api/v1/workspaces/" + workspaceId + "/custom-fields", "key", key), "custom field " + key);
    }

    private boolean containsArrayRecord(AuthSession session, String path, String field, String value) {
        JsonNode rows = session.requireJson(session.get(path), 200);
        for (JsonNode row : rows) {
            if (value.equals(row.path(field).asText())) {
                return true;
            }
        }
        return false;
    }

    private void waitFor(Check check, String label) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + label, ex);
            }
        }
        throw new AssertionError("Timed out waiting for " + label);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private String jsString(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
