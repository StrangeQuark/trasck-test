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
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for authenticated API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
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
            assertEquals(teamId, session.requireJson(session.get("/api/v1/teams/" + teamId), 200).path("id").asText());
            JsonNode updatedTeam = session.requireJson(session.patch("/api/v1/teams/" + teamId, JsonSupport.object(
                    "description", "Updated by the external Java Playwright API suite",
                    "defaultCapacity", 75
            )), 200);
            assertEquals(75, updatedTeam.path("defaultCapacity").asInt(), updatedTeam.toString());

            JsonNode membership = session.requireJson(session.post("/api/v1/teams/" + teamId + "/memberships", JsonSupport.object(
                    "userId", session.userId(),
                    "role", "developer",
                    "capacityPercent", 100
            )), 201);
            cleanup.delete(session, "/api/v1/teams/" + teamId + "/memberships/" + session.userId());
            assertEquals(session.userId(), membership.path("userId").asText(), membership.toString());
            assertTrue(session.requireJson(session.get("/api/v1/teams/" + teamId + "/memberships"), 200).isArray());

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
            assertEquals(iterationId, session.requireJson(session.get("/api/v1/iterations/" + iterationId), 200).path("id").asText());
            JsonNode updatedIteration = session.requireJson(session.patch("/api/v1/iterations/" + iterationId, JsonSupport.object(
                    "name", "Playwright Sprint Updated " + suffix,
                    "committedPoints", 3
            )), 200);
            assertEquals("Playwright Sprint Updated " + suffix, updatedIteration.path("name").asText(), updatedIteration.toString());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/iterations"), 200).isArray());

            JsonNode iterationStory = createStory(session, workspace.projectId(), "Playwright iteration story " + suffix);
            String iterationStoryId = iterationStory.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + iterationStoryId);
            JsonNode iterationScope = session.requireJson(session.post("/api/v1/iterations/" + iterationId + "/work-items", JsonSupport.object(
                    "workItemId", iterationStoryId
            )), 201);
            assertEquals(iterationStoryId, iterationScope.path("workItemId").asText(), iterationScope.toString());
            assertTrue(session.requireJson(session.get("/api/v1/iterations/" + iterationId + "/work-items"), 200).isArray());
            APIResponse removeIterationScope = session.delete("/api/v1/iterations/" + iterationId + "/work-items/" + iterationStoryId);
            assertTrue(removeIterationScope.status() == 200 || removeIterationScope.status() == 204, removeIterationScope.text());
            JsonNode committedIteration = session.requireJson(session.post("/api/v1/iterations/" + iterationId + "/commit", JsonSupport.object(
                    "committedPoints", 3,
                    "activate", true
            )), 200);
            assertEquals("active", committedIteration.path("status").asText(), committedIteration.toString());
            JsonNode closedIteration = session.requireJson(session.post("/api/v1/iterations/" + iterationId + "/close", JsonSupport.object(
                    "completedPoints", 0,
                    "carryOverIncomplete", false
            )), 200);
            assertEquals("closed", closedIteration.path("status").asText(), closedIteration.toString());

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
            assertEquals(releaseId, session.requireJson(session.get("/api/v1/releases/" + releaseId), 200).path("id").asText());
            JsonNode updatedRelease = session.requireJson(session.patch("/api/v1/releases/" + releaseId, JsonSupport.object(
                    "description", "Updated by Playwright API coverage",
                    "status", "active"
            )), 200);
            assertEquals("active", updatedRelease.path("status").asText(), updatedRelease.toString());
            JsonNode releaseScope = session.requireJson(session.post("/api/v1/releases/" + releaseId + "/work-items", JsonSupport.object(
                    "workItemId", iterationStoryId
            )), 201);
            assertEquals(iterationStoryId, releaseScope.path("workItemId").asText(), releaseScope.toString());
            assertTrue(session.requireJson(session.get("/api/v1/releases/" + releaseId + "/work-items"), 200).isArray());
            APIResponse removeReleaseScope = session.delete("/api/v1/releases/" + releaseId + "/work-items/" + iterationStoryId);
            assertTrue(removeReleaseScope.status() == 200 || removeReleaseScope.status() == 204, removeReleaseScope.text());
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
            assertEquals(roadmapId, session.requireJson(session.get("/api/v1/roadmaps/" + roadmapId), 200).path("id").asText());
            JsonNode updatedRoadmap = session.requireJson(session.patch("/api/v1/roadmaps/" + roadmapId, JsonSupport.object(
                    "name", "Playwright Roadmap Updated " + suffix,
                    "visibility", "project"
            )), 200);
            assertEquals("Playwright Roadmap Updated " + suffix, updatedRoadmap.path("name").asText(), updatedRoadmap.toString());
            JsonNode roadmapItem = session.requireJson(session.post("/api/v1/roadmaps/" + roadmapId + "/items", JsonSupport.object(
                    "workItemId", iterationStoryId,
                    "startDate", "2026-05-01",
                    "endDate", "2026-05-15",
                    "position", 1,
                    "displayConfig", Map.of("lane", "delivery")
            )), 201);
            String roadmapItemId = roadmapItem.path("id").asText();
            cleanup.delete(session, "/api/v1/roadmaps/" + roadmapId + "/items/" + roadmapItemId);
            assertEquals(iterationStoryId, roadmapItem.path("workItemId").asText(), roadmapItem.toString());
            JsonNode updatedRoadmapItem = session.requireJson(session.patch("/api/v1/roadmaps/" + roadmapId + "/items/" + roadmapItemId, JsonSupport.object(
                    "position", 2
            )), 200);
            assertEquals(2, updatedRoadmapItem.path("position").asInt(), updatedRoadmapItem.toString());
            assertTrue(session.requireJson(session.get("/api/v1/roadmaps/" + roadmapId + "/items"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/roadmaps"), 200).isArray());
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
            assertEquals(boardId, session.requireJson(session.get("/api/v1/boards/" + boardId), 200).path("id").asText());
            JsonNode updatedBoard = session.requireJson(session.patch("/api/v1/boards/" + boardId, JsonSupport.object(
                    "name", "Playwright Board Updated " + suffix
            )), 200);
            assertEquals("Playwright Board Updated " + suffix, updatedBoard.path("name").asText(), updatedBoard.toString());

            APIResponse columnResponse = session.post("/api/v1/boards/" + boardId + "/columns", JsonSupport.object(
                    "name", "Playwright Column",
                    "statusIds", List.of(),
                    "position", 99,
                    "doneColumn", false
            ));
            ApiDiagnostics.writeSnippet("board-column-create", "POST board column", columnResponse);
            JsonNode column = session.requireJson(columnResponse, 201);
            String columnId = column.path("id").asText();
            cleanup.delete(session, "/api/v1/boards/" + boardId + "/columns/" + columnId);
            assertEquals("Playwright Column", column.path("name").asText());
            JsonNode updatedColumn = session.requireJson(session.patch("/api/v1/boards/" + boardId + "/columns/" + columnId, JsonSupport.object(
                    "name", "Playwright Column Updated",
                    "position", 100
            )), 200);
            assertEquals("Playwright Column Updated", updatedColumn.path("name").asText(), updatedColumn.toString());
            assertTrue(session.requireJson(session.get("/api/v1/boards/" + boardId + "/columns"), 200).isArray());

            JsonNode swimlane = session.requireJson(session.post("/api/v1/boards/" + boardId + "/swimlanes", JsonSupport.object(
                    "name", "Playwright Swimlane",
                    "swimlaneType", "query",
                    "query", Map.of("projectId", workspace.projectId(), "entityType", "work_item"),
                    "position", 1,
                    "enabled", true
            )), 201);
            String swimlaneId = swimlane.path("id").asText();
            cleanup.delete(session, "/api/v1/boards/" + boardId + "/swimlanes/" + swimlaneId);
            assertEquals("Playwright Swimlane", swimlane.path("name").asText(), swimlane.toString());
            JsonNode updatedSwimlane = session.requireJson(session.patch("/api/v1/boards/" + boardId + "/swimlanes/" + swimlaneId, JsonSupport.object(
                    "name", "Playwright Swimlane Updated",
                    "position", 2
            )), 200);
            assertEquals("Playwright Swimlane Updated", updatedSwimlane.path("name").asText(), updatedSwimlane.toString());
            assertTrue(session.requireJson(session.get("/api/v1/boards/" + boardId + "/swimlanes"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/boards/" + boardId + "/work-items"), 200).path("columns").isArray());

            JsonNode boardStory = createStory(session, workspace.projectId(), "Playwright board story " + suffix);
            String boardStoryId = boardStory.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + boardStoryId);
            JsonNode boardPeer = createStory(session, workspace.projectId(), "Playwright board peer story " + suffix);
            String boardPeerId = boardPeer.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + boardPeerId);
            JsonNode assigned = session.requireJson(session.post("/api/v1/work-items/" + boardStoryId + "/assign", JsonSupport.object(
                    "assigneeId", session.userId()
            )), 200);
            assertEquals(session.userId(), assigned.path("assigneeId").asText(), assigned.toString());
            JsonNode teamAssigned = session.requireJson(session.post("/api/v1/work-items/" + boardStoryId + "/team", JsonSupport.object(
                    "teamId", teamId
            )), 200);
            assertEquals(teamId, teamAssigned.path("teamId").asText(), teamAssigned.toString());
            assertEquals(boardStoryId, session.requireJson(session.post("/api/v1/boards/" + boardId + "/work-items/" + boardStoryId + "/rank", JsonSupport.object(
                    "previousWorkItemId", boardPeerId
            )), 200).path("id").asText());
            assertEquals(boardStoryId, session.requireJson(session.post("/api/v1/boards/" + boardId + "/work-items/" + boardStoryId + "/move", JsonSupport.object(
                    "nextWorkItemId", boardPeerId
            )), 200).path("id").asText());
            JsonNode boardTransitioned = session.requireJson(session.post("/api/v1/boards/" + boardId + "/work-items/" + boardStoryId + "/transition", JsonSupport.object(
                    "transitionKey", "open_to_ready"
            )), 200);
            assertEquals(boardStoryId, boardTransitioned.path("id").asText(), boardTransitioned.toString());
        }
    }

    private JsonNode createStory(AuthSession session, String projectId, String title) {
        APIResponse response = session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", title,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by planning/board Playwright API coverage.",
                "visibility", "inherited"
        ));
        ApiDiagnostics.writeSnippet("planning-board-work-item-create", "POST project work item", response);
        return session.requireJson(response, 201);
    }
}
