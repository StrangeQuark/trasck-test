package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("planning")
class PlanningBoardApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void authenticatedUsersCanCreateListAndCleanUpPlanningAndBoardResources() {
        assumeTrue(config.hasLoginCredentials(), "Set TRASCK_E2E_LOGIN_IDENTIFIER and TRASCK_E2E_LOGIN_PASSWORD for authenticated API coverage");
        TestWorkspace workspace = TestWorkspace.require(config);
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            String suffix = UniqueData.suffix();

            JsonNode team = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/teams", JsonSupport.object(
                    "name", "Playwright Team " + suffix,
                    "description", "Created by the external Java Playwright API suite",
                    "leadUserId", session.userId(),
                    "defaultCapacity", 80
            )), 201);
            String teamId = team.path("id").asText();
            cleanup.delete(session, "/api/v1/teams/" + teamId);
            assertEquals("active", team.path("status").asText());

            JsonNode projectTeam = session.requireJson(session.put("/api/v1/projects/" + workspace.projectId() + "/teams/" + teamId, JsonSupport.object(
                    "role", "delivery"
            )), 200);
            cleanup.delete(session, "/api/v1/projects/" + workspace.projectId() + "/teams/" + teamId);
            assertEquals("delivery", projectTeam.path("role").asText());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/teams"), 200).isArray());

            JsonNode iteration = session.requireJson(session.post("/api/v1/projects/" + workspace.projectId() + "/iterations", JsonSupport.object(
                    "name", "Playwright Sprint " + suffix,
                    "teamId", teamId,
                    "startDate", "2026-04-21",
                    "endDate", "2026-05-05"
            )), 201);
            String iterationId = iteration.path("id").asText();
            cleanup.delete(session, "/api/v1/iterations/" + iterationId);
            assertEquals("planned", iteration.path("status").asText());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/iterations"), 200).isArray());

            JsonNode release = session.requireJson(session.post("/api/v1/projects/" + workspace.projectId() + "/releases", JsonSupport.object(
                    "name", "Playwright Release " + suffix,
                    "version", "e2e-" + suffix,
                    "releaseDate", "2026-06-01",
                    "status", "planned",
                    "description", "Created by Playwright API coverage"
            )), 201);
            String releaseId = release.path("id").asText();
            cleanup.delete(session, "/api/v1/releases/" + releaseId);
            assertEquals("planned", release.path("status").asText());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/releases"), 200).isArray());

            JsonNode roadmap = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/roadmaps", JsonSupport.object(
                    "projectId", workspace.projectId(),
                    "name", "Playwright Roadmap " + suffix,
                    "visibility", "project",
                    "config", Map.of("lane", "delivery")
            )), 201);
            String roadmapId = roadmap.path("id").asText();
            cleanup.delete(session, "/api/v1/roadmaps/" + roadmapId);
            assertEquals("project", roadmap.path("visibility").asText());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/roadmaps"), 200).isArray());

            JsonNode board = session.requireJson(session.post("/api/v1/projects/" + workspace.projectId() + "/boards", JsonSupport.object(
                    "name", "Playwright Board " + suffix,
                    "type", "kanban",
                    "teamId", teamId,
                    "filterConfig", Map.of("createdBy", "playwright"),
                    "active", true
            )), 201);
            String boardId = board.path("id").asText();
            cleanup.delete(session, "/api/v1/boards/" + boardId);
            assertEquals("kanban", board.path("type").asText());

            APIResponse columnResponse = session.post("/api/v1/boards/" + boardId + "/columns", JsonSupport.object(
                    "name", "Playwright Column",
                    "statusIds", List.of(),
                    "position", 99,
                    "doneColumn", false
            ));
            ApiDiagnostics.writeSnippet("board-column-create", "POST board column", columnResponse);
            JsonNode column = session.requireJson(columnResponse, 201);
            cleanup.delete(session, "/api/v1/boards/" + boardId + "/columns/" + column.path("id").asText());
            assertEquals("Playwright Column", column.path("name").asText());
            assertTrue(session.requireJson(session.get("/api/v1/boards/" + boardId + "/columns"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/boards/" + boardId + "/work-items"), 200).path("columns").isArray());
        }
    }
}
