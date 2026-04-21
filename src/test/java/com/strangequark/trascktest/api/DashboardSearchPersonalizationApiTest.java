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
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("dashboards")
@Tag("search")
class DashboardSearchPersonalizationApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void authenticatedUsersCanCreateRenderAndCleanUpDashboardSearchAndPersonalizationResources() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for authenticated API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();

            JsonNode team = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/teams", JsonSupport.object(
                    "name", "Playwright Search Team " + suffix,
                    "description", "Created for team-scoped dashboard/search coverage",
                    "leadUserId", session.userId(),
                    "defaultCapacity", 80
            )), 201);
            String teamId = team.path("id").asText();
            cleanup.delete(session, "/api/v1/teams/" + teamId);

            JsonNode savedFilter = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/saved-filters", JsonSupport.object(
                    "name", "Playwright Filter " + suffix,
                    "visibility", "project",
                    "projectId", workspace.projectId(),
                    "query", Map.of("projectId", workspace.projectId(), "entityType", "work_item")
            )), 201);
            String savedFilterId = savedFilter.path("id").asText();
            cleanup.delete(session, "/api/v1/saved-filters/" + savedFilterId);
            assertEquals("project", savedFilter.path("visibility").asText());
            assertEquals(savedFilterId, session.requireJson(session.get("/api/v1/saved-filters/" + savedFilterId), 200).path("id").asText());
            JsonNode updatedSavedFilter = session.requireJson(session.patch("/api/v1/saved-filters/" + savedFilterId, JsonSupport.object(
                    "name", "Playwright Filter Updated " + suffix
            )), 200);
            assertEquals("Playwright Filter Updated " + suffix, updatedSavedFilter.path("name").asText(), updatedSavedFilter.toString());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/saved-filters"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/saved-filters"), 200).isArray());
            JsonNode executed = session.requireJson(session.get("/api/v1/saved-filters/" + savedFilterId + "/work-items?limit=5"), 200);
            assertTrue(executed.path("items").isArray(), executed.toString());

            JsonNode teamSavedFilter = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/saved-filters", JsonSupport.object(
                    "name", "Playwright Team Filter " + suffix,
                    "visibility", "team",
                    "teamId", teamId,
                    "query", Map.of("projectId", workspace.projectId(), "entityType", "work_item")
            )), 201);
            cleanup.delete(session, "/api/v1/saved-filters/" + teamSavedFilter.path("id").asText());
            assertEquals(teamId, teamSavedFilter.path("teamId").asText(), teamSavedFilter.toString());
            assertTrue(session.requireJson(session.get("/api/v1/teams/" + teamId + "/saved-filters"), 200).isArray());

            JsonNode dashboard = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/dashboards", JsonSupport.object(
                    "name", "Playwright Dashboard " + suffix,
                    "visibility", "project",
                    "projectId", workspace.projectId(),
                    "layout", Map.of("columns", 12)
            )), 201);
            String dashboardId = dashboard.path("id").asText();
            cleanup.delete(session, "/api/v1/dashboards/" + dashboardId);
            assertEquals("project", dashboard.path("visibility").asText());
            assertEquals(dashboardId, session.requireJson(session.get("/api/v1/dashboards/" + dashboardId), 200).path("id").asText());
            JsonNode updatedDashboard = session.requireJson(session.patch("/api/v1/dashboards/" + dashboardId, JsonSupport.object(
                    "name", "Playwright Dashboard Updated " + suffix
            )), 200);
            assertEquals("Playwright Dashboard Updated " + suffix, updatedDashboard.path("name").asText(), updatedDashboard.toString());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/dashboards"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/dashboards"), 200).isArray());

            JsonNode teamDashboard = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/dashboards", JsonSupport.object(
                    "name", "Playwright Team Dashboard " + suffix,
                    "visibility", "team",
                    "teamId", teamId,
                    "layout", Map.of("columns", 12)
            )), 201);
            cleanup.delete(session, "/api/v1/dashboards/" + teamDashboard.path("id").asText());
            assertEquals(teamId, teamDashboard.path("teamId").asText(), teamDashboard.toString());
            assertTrue(session.requireJson(session.get("/api/v1/teams/" + teamId + "/dashboards"), 200).isArray());

            APIResponse widgetResponse = session.post("/api/v1/dashboards/" + dashboardId + "/widgets", JsonSupport.object(
                    "widgetType", "custom_summary",
                    "title", "Playwright Widget",
                    "config", Map.of("reportType", "custom_summary"),
                    "positionX", 0,
                    "positionY", 0,
                    "width", 4,
                    "height", 3
            ));
            ApiDiagnostics.writeSnippet("dashboard-widget-create", "POST dashboard widget", widgetResponse);
            JsonNode widget = session.requireJson(widgetResponse, 201);
            cleanup.delete(session, "/api/v1/dashboards/" + dashboardId + "/widgets/" + widget.path("id").asText());
            assertEquals("custom_summary", widget.path("widgetType").asText());
            JsonNode updatedWidget = session.requireJson(session.patch("/api/v1/dashboards/" + dashboardId + "/widgets/" + widget.path("id").asText(), JsonSupport.object(
                    "title", "Playwright Widget Updated",
                    "positionX", 1
            )), 200);
            assertEquals("Playwright Widget Updated", updatedWidget.path("title").asText(), updatedWidget.toString());
            JsonNode rendered = session.requireJson(session.get("/api/v1/dashboards/" + dashboardId + "/render"), 200);
            assertTrue(rendered.path("widgets").isArray(), rendered.toString());

            JsonNode savedView = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/views", JsonSupport.object(
                    "name", "Playwright View " + suffix,
                    "viewType", "work_item_list",
                    "config", Map.of("savedFilterId", savedFilterId),
                    "visibility", "project",
                    "projectId", workspace.projectId()
            )), 201);
            String viewId = savedView.path("id").asText();
            cleanup.delete(session, "/api/v1/personalization/views/" + viewId);
            assertEquals("work_item_list", savedView.path("viewType").asText());
            assertEquals(viewId, session.requireJson(session.get("/api/v1/personalization/views/" + viewId), 200).path("id").asText());
            JsonNode updatedView = session.requireJson(session.patch("/api/v1/personalization/views/" + viewId, JsonSupport.object(
                    "name", "Playwright View Updated " + suffix
            )), 200);
            assertEquals("Playwright View Updated " + suffix, updatedView.path("name").asText(), updatedView.toString());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/views"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/personalization/views"), 200).isArray());

            JsonNode teamSavedView = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/views", JsonSupport.object(
                    "name", "Playwright Team View " + suffix,
                    "viewType", "work_item_list",
                    "config", Map.of("savedFilterId", teamSavedFilter.path("id").asText()),
                    "visibility", "team",
                    "teamId", teamId
            )), 201);
            cleanup.delete(session, "/api/v1/personalization/views/" + teamSavedView.path("id").asText());
            assertEquals(teamId, teamSavedView.path("teamId").asText(), teamSavedView.toString());
            assertTrue(session.requireJson(session.get("/api/v1/teams/" + teamId + "/personalization/views"), 200).isArray());

            JsonNode teamReportQuery = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/report-query-catalog", JsonSupport.object(
                    "queryKey", "pw_team_summary_" + suffix,
                    "name", "Playwright Team Summary " + suffix,
                    "description", "Created by Java Playwright team-scoped search coverage",
                    "queryType", "project_dashboard_summary",
                    "queryConfig", Map.of("projectId", workspace.projectId(), "teamId", teamId),
                    "parametersSchema", Map.of("type", "object", "additionalProperties", false),
                    "visibility", "team",
                    "teamId", teamId,
                    "enabled", true
            )), 201);
            cleanup.delete(session, "/api/v1/report-query-catalog/" + teamReportQuery.path("id").asText());
            assertEquals(teamId, teamReportQuery.path("teamId").asText(), teamReportQuery.toString());
            assertTrue(session.requireJson(session.get("/api/v1/teams/" + teamId + "/report-query-catalog"), 200).isArray());

            JsonNode favorite = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/favorites", JsonSupport.object(
                    "entityType", "dashboard",
                    "entityId", dashboardId
            )), 201);
            cleanup.delete(session, "/api/v1/personalization/favorites/" + favorite.path("id").asText());
            assertEquals("dashboard", favorite.path("entityType").asText());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/favorites"), 200).isArray());

            JsonNode recentItem = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/recent-items", JsonSupport.object(
                    "entityType", "saved_filter",
                    "entityId", savedFilterId
            )), 200);
            cleanup.delete(session, "/api/v1/personalization/recent-items/" + recentItem.path("id").asText());
            assertEquals("saved_filter", recentItem.path("entityType").asText());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/recent-items"), 200).isArray());
        }
    }
}
