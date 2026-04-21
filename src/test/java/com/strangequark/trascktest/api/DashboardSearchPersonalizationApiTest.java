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
        assumeTrue(config.hasLoginCredentials(), "Set TRASCK_E2E_LOGIN_IDENTIFIER and TRASCK_E2E_LOGIN_PASSWORD for authenticated API coverage");
        TestWorkspace workspace = TestWorkspace.require(config);
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            String suffix = UniqueData.suffix();

            JsonNode savedFilter = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/saved-filters", JsonSupport.object(
                    "name", "Playwright Filter " + suffix,
                    "visibility", "project",
                    "projectId", workspace.projectId(),
                    "query", Map.of("projectId", workspace.projectId(), "entityType", "work_item")
            )), 201);
            String savedFilterId = savedFilter.path("id").asText();
            cleanup.delete(session, "/api/v1/saved-filters/" + savedFilterId);
            assertEquals("project", savedFilter.path("visibility").asText());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/saved-filters"), 200).isArray());
            JsonNode executed = session.requireJson(session.get("/api/v1/saved-filters/" + savedFilterId + "/work-items?limit=5"), 200);
            assertTrue(executed.path("items").isArray(), executed.toString());

            JsonNode dashboard = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/dashboards", JsonSupport.object(
                    "name", "Playwright Dashboard " + suffix,
                    "visibility", "project",
                    "projectId", workspace.projectId(),
                    "layout", Map.of("columns", 12)
            )), 201);
            String dashboardId = dashboard.path("id").asText();
            cleanup.delete(session, "/api/v1/dashboards/" + dashboardId);
            assertEquals("project", dashboard.path("visibility").asText());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/dashboards"), 200).isArray());

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

            JsonNode favorite = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/favorites", JsonSupport.object(
                    "entityType", "dashboard",
                    "entityId", dashboardId
            )), 201);
            cleanup.delete(session, "/api/v1/personalization/favorites/" + favorite.path("id").asText());
            assertEquals("dashboard", favorite.path("entityType").asText());

            JsonNode recentItem = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/recent-items", JsonSupport.object(
                    "entityType", "saved_filter",
                    "entityId", savedFilterId
            )), 200);
            cleanup.delete(session, "/api/v1/personalization/recent-items/" + recentItem.path("id").asText());
            assertEquals("saved_filter", recentItem.path("entityType").asText());
        }
    }
}
