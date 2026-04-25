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
            String backlogStoryTitle = "Browser Planning Backlog Story " + suffix;

            Page page = browserSession.page();
            installFrontendContext(page, workspace, seed);
            loginThroughUi(page, login);

            page.navigate("/planning");
            assertThat(page.locator("xpath=//h2[normalize-space()='Sprint Flow']").first()).isVisible();
            page.navigate("/planning/admin");
            assertThat(page.locator("xpath=//h2[normalize-space()='Teams']").first()).isVisible();

            panel(page, "Teams").getByLabel("Team name").fill(teamName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create team")).click();
            JsonNode team = waitForRecordByName(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/teams", teamName);
            String teamId = team.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/teams/" + teamId);

            panel(page, "Project Team").getByLabel("Project team").selectOption(teamId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign")).click();
            assertThat(page.getByText("delivery").first()).isVisible();
            cleanup.delete(apiSession, "/api/v1/projects/" + workspace.projectId() + "/teams/" + teamId);

            Locator iterations = panel(page, "Iterations");
            iterations.getByLabel("Iteration name").fill(iterationName);
            iterations.getByLabel("Iteration team").selectOption(teamId);
            iterations.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Create").setExact(true)).click();
            JsonNode iteration = waitForRecordByName(apiSession, "/api/v1/projects/" + workspace.projectId() + "/iterations", iterationName);
            cleanup.delete(apiSession, "/api/v1/iterations/" + iteration.path("id").asText());

            Locator board = panel(page, "Board");
            board.getByLabel("Board name").fill(boardName);
            board.getByLabel("Board team").selectOption(teamId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create board")).click();
            JsonNode boardRecord = waitForRecordByName(apiSession, "/api/v1/projects/" + workspace.projectId() + "/boards", boardName);
            String boardId = boardRecord.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/boards/" + boardId);

            Locator layout = panel(page, "Board Layout");
            layout.getByLabel("Board").selectOption(boardId);
            layout.getByLabel("Column name").fill(columnName);
            layout.locator(".status-option input").first().check();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add column")).click();
            JsonNode column = waitForRecordByName(apiSession, "/api/v1/boards/" + boardId + "/columns", columnName);
            cleanup.delete(apiSession, "/api/v1/boards/" + boardId + "/columns/" + column.path("id").asText());
            layout.getByLabel("Swimlane name").fill(swimlaneName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add swimlane")).click();
            JsonNode swimlane = waitForRecordByName(apiSession, "/api/v1/boards/" + boardId + "/swimlanes", swimlaneName);
            cleanup.delete(apiSession, "/api/v1/boards/" + boardId + "/swimlanes/" + swimlane.path("id").asText());

            Locator release = panel(page, "Release");
            release.getByLabel("Release name").fill(releaseName);
            release.getByLabel("Release version").fill("ui-" + suffix);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create release")).click();
            JsonNode releaseRecord = waitForRecordByName(apiSession, "/api/v1/projects/" + workspace.projectId() + "/releases", releaseName);
            cleanup.delete(apiSession, "/api/v1/releases/" + releaseRecord.path("id").asText());
            release.getByLabel("Work item").selectOption(seed.workItem().path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add work")).click();
            cleanup.delete(apiSession, "/api/v1/releases/" + releaseRecord.path("id").asText()
                    + "/work-items/" + seed.workItem().path("id").asText());

            Locator roadmap = panel(page, "Roadmap");
            roadmap.getByLabel("Roadmap name").fill(roadmapName);
            roadmap.getByLabel("Roadmap visibility").selectOption("workspace");
            roadmap.getByLabel("Roadmap lanes").fill("delivery");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create roadmap")).click();
            JsonNode roadmapRecord = waitForRecordByName(apiSession, "/api/v1/projects/" + workspace.projectId() + "/roadmaps", roadmapName);
            cleanup.delete(apiSession, "/api/v1/roadmaps/" + roadmapRecord.path("id").asText());
            roadmap.getByLabel("Roadmap").selectOption(roadmapRecord.path("id").asText());
            roadmap.getByLabel("Work item").selectOption(seed.workItem().path("id").asText());
            roadmap.getByLabel("Roadmap item lane").fill("delivery");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add item")).click();
            JsonNode roadmapItem = waitForRoadmapItem(apiSession, roadmapRecord.path("id").asText(), seed.workItem().path("id").asText());
            cleanup.delete(apiSession, "/api/v1/roadmaps/" + roadmapRecord.path("id").asText()
                    + "/items/" + roadmapItem.path("id").asText());

            JsonNode backlogStory = createStory(apiSession, workspace.projectId(), backlogStoryTitle);
            String backlogStoryId = backlogStory.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/work-items/" + backlogStoryId);
            apiSession.requireJson(apiSession.post("/api/v1/work-items/" + backlogStoryId + "/team", JsonSupport.object(
                    "teamId", teamId
            )), 200);

            page.navigate("/planning/backlog");
            assertThat(page.locator("xpath=//h2[normalize-space()='Backlog']").first()).isVisible();
            page.getByLabel("Iteration").selectOption(iteration.path("id").asText());
            assertThat(page.getByText(backlogStoryTitle).first()).isVisible();
            page.locator(".planning-work-row").filter(new Locator.FilterOptions().setHasText(backlogStoryTitle))
                    .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Plan"))
                    .click();
            waitForIterationScopedWorkItem(apiSession, iteration.path("id").asText(), backlogStoryId);
            assertThat(page.locator(".planning-work-row").filter(new Locator.FilterOptions().setHasText(backlogStoryTitle))).hasCount(1);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Start sprint")).click();
            waitForIterationStatus(apiSession, iteration.path("id").asText(), "active");

            page.navigate("/planning/active-board");
            assertThat(page.locator("xpath=//h2[normalize-space()='Active Sprint Board']").first()).isVisible();
            page.getByLabel("Iteration").selectOption(iteration.path("id").asText());
            page.getByLabel("Board").selectOption(boardId);
            assertThat(page.getByText(backlogStoryTitle).first()).isVisible();

            page.navigate("/planning/boards/" + boardId);
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Board Detail"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Reload")).click();

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

    private JsonNode waitForRecordByName(AuthSession session, String path, String name) {
        return waitForRecordByField(session, path, "name", name);
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

    private JsonNode waitForRoadmapItem(AuthSession session, String roadmapId, String workItemId) {
        long deadline = System.currentTimeMillis() + 5_000;
        AssertionError lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            JsonNode rows = session.requireJson(session.get("/api/v1/roadmaps/" + roadmapId + "/items"), 200);
            for (JsonNode row : rows) {
                if (workItemId.equals(row.path("workItemId").asText())) {
                    return row;
                }
            }
            lastFailure = new AssertionError("Could not find roadmap item for workItemId=" + workItemId + ": " + rows);
            try {
                Thread.sleep(150);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw lastFailure;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("Could not find roadmap item for workItemId=" + workItemId);
    }

    private JsonNode createStory(AuthSession session, String projectId, String title) {
        return session.requireJson(session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", title,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by planning backlog/board browser coverage.",
                "visibility", "inherited"
        )), 201);
    }

    private void waitForIterationScopedWorkItem(AuthSession session, String iterationId, String workItemId) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode rows = session.requireJson(session.get("/api/v1/iterations/" + iterationId + "/work-items"), 200);
            for (JsonNode row : rows) {
                if (workItemId.equals(row.path("workItemId").asText()) && row.path("removedAt").isNull()) {
                    return;
                }
            }
            sleep();
        }
        throw new AssertionError("Could not find iteration-scoped work item " + workItemId + " on iteration " + iterationId);
    }

    private void waitForIterationStatus(AuthSession session, String iterationId, String status) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode iteration = session.requireJson(session.get("/api/v1/iterations/" + iterationId), 200);
            if (status.equals(iteration.path("status").asText())) {
                return;
            }
            sleep();
        }
        throw new AssertionError("Iteration " + iterationId + " did not reach status " + status);
    }

    private void sleep() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", interrupted);
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
