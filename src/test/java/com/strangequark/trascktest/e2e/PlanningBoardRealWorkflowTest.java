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
@Tag("planning")
class PlanningBoardRealWorkflowTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void realBackendPlanningBoardReleaseAndRoadmapWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for real frontend planning workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "planning-board-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            SampleDataFixture.SampleDataContext seed = SampleDataFixture.create(apiSession, workspace, cleanup);
            String suffix = UniqueData.suffix();
            String teamName = "Browser Planning Team " + suffix;
            String iterationName = "Browser Planning Sprint " + suffix;
            String boardName = "Browser Planning Board " + suffix;
            String columnName = "Browser Planning Ready " + suffix;
            String swimlaneName = "Browser Planning Lane " + suffix;
            String releaseName = "Browser Planning Release " + suffix;
            String roadmapName = "Browser Planning Roadmap " + suffix;

            Page page = browserSession.page();
            installFrontendContext(page, workspace, seed);
            loginThroughUi(page, login);

            page.navigate("/planning");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Teams"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            assertThat(page.getByText(seed.workItem().path("title").asText()).first()).isVisible();

            panel(page, "Teams").getByLabel("Name").fill(teamName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create team")).click();
            assertThat(page.getByText(teamName).first()).isVisible();
            JsonNode team = requireRecordByName(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/teams", teamName);
            String teamId = team.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/teams/" + teamId);

            panel(page, "Project Team").getByLabel("Team").selectOption(teamId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign")).click();
            assertThat(page.getByText("delivery").first()).isVisible();
            cleanup.delete(apiSession, "/api/v1/projects/" + workspace.projectId() + "/teams/" + teamId);

            Locator iterations = panel(page, "Iterations");
            iterations.getByLabel("Name").fill(iterationName);
            iterations.getByLabel("Team").selectOption(teamId);
            iterations.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Create").setExact(true)).click();
            assertThat(page.getByText(iterationName).first()).isVisible();
            JsonNode iteration = requireRecordByName(apiSession, "/api/v1/projects/" + workspace.projectId() + "/iterations", iterationName);
            cleanup.delete(apiSession, "/api/v1/iterations/" + iteration.path("id").asText());

            Locator board = panel(page, "Board");
            board.getByLabel("Name").fill(boardName);
            board.getByLabel("Team").selectOption(teamId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create board")).click();
            assertThat(page.getByText(boardName).first()).isVisible();
            JsonNode boardRecord = requireRecordByName(apiSession, "/api/v1/projects/" + workspace.projectId() + "/boards", boardName);
            String boardId = boardRecord.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/boards/" + boardId);

            Locator layout = panel(page, "Board Layout");
            layout.getByLabel("Column").fill(columnName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add column")).click();
            assertThat(page.getByText(columnName).first()).isVisible();
            JsonNode column = requireRecordByName(apiSession, "/api/v1/boards/" + boardId + "/columns", columnName);
            cleanup.delete(apiSession, "/api/v1/boards/" + boardId + "/columns/" + column.path("id").asText());
            layout.getByLabel("Swimlane").fill(swimlaneName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add swimlane")).click();
            assertThat(page.getByText(swimlaneName).first()).isVisible();
            JsonNode swimlane = requireRecordByName(apiSession, "/api/v1/boards/" + boardId + "/swimlanes", swimlaneName);
            cleanup.delete(apiSession, "/api/v1/boards/" + boardId + "/swimlanes/" + swimlane.path("id").asText());

            Locator release = panel(page, "Release");
            release.getByLabel("Name").fill(releaseName);
            release.getByLabel("Version").fill("ui-" + suffix);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create release")).click();
            assertThat(page.getByText(releaseName).first()).isVisible();
            JsonNode releaseRecord = requireRecordByName(apiSession, "/api/v1/projects/" + workspace.projectId() + "/releases", releaseName);
            cleanup.delete(apiSession, "/api/v1/releases/" + releaseRecord.path("id").asText());
            release.getByLabel("Work item").selectOption(seed.workItem().path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add work")).click();
            assertThat(page.getByText(seed.workItem().path("id").asText()).first()).isVisible();
            cleanup.delete(apiSession, "/api/v1/releases/" + releaseRecord.path("id").asText()
                    + "/work-items/" + seed.workItem().path("id").asText());

            Locator roadmap = panel(page, "Roadmap");
            roadmap.getByLabel("Name").fill(roadmapName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create roadmap")).click();
            assertThat(page.getByText(roadmapName).first()).isVisible();
            JsonNode roadmapRecord = requireRecordByName(apiSession, "/api/v1/projects/" + workspace.projectId() + "/roadmaps", roadmapName);
            cleanup.delete(apiSession, "/api/v1/roadmaps/" + roadmapRecord.path("id").asText());
            roadmap.getByLabel("Work item").selectOption(seed.workItem().path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add item")).click();
            assertThat(page.getByText(seed.workItem().path("id").asText()).first()).isVisible();
            JsonNode roadmapItem = requireRecordByField(
                    apiSession,
                    "/api/v1/roadmaps/" + roadmapRecord.path("id").asText() + "/items",
                    "workItemId",
                    seed.workItem().path("id").asText()
            );
            cleanup.delete(apiSession, "/api/v1/roadmaps/" + roadmapRecord.path("id").asText()
                    + "/items/" + roadmapItem.path("id").asText());

            page.navigate("/planning/boards/" + boardId);
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Board Detail"))).isVisible();
            assertThat(page.getByText(boardName).first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Reload")).click();
            assertThat(page.getByText(columnName).first()).isVisible();
            assertThat(page.getByText(swimlaneName).first()).isVisible();

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
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

    private JsonNode requireRecordByName(AuthSession session, String path, String name) {
        return requireRecordByField(session, path, "name", name);
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
