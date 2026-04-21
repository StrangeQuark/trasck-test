package com.strangequark.trascktest.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIResponse;
import com.strangequark.trascktest.config.TrasckTestConfig;
import java.util.Map;

public final class SampleDataFixture {
    private SampleDataFixture() {
    }

    public static SampleDataContext ensure(TrasckTestConfig config, AuthSession session, TestWorkspace workspace, ApiCleanup cleanup) {
        if (!config.seedSampleData()) {
            return SampleDataContext.empty();
        }
        String suffix = UniqueData.suffix();
        JsonNode story = createStory(session, workspace.projectId(), suffix);
        cleanup.delete(session, "/api/v1/work-items/" + story.path("id").asText());
        JsonNode savedFilter = createSavedFilter(session, workspace, suffix);
        cleanup.delete(session, "/api/v1/saved-filters/" + savedFilter.path("id").asText());
        JsonNode dashboard = createDashboard(session, workspace, suffix);
        cleanup.delete(session, "/api/v1/dashboards/" + dashboard.path("id").asText());
        return new SampleDataContext(story, savedFilter, dashboard);
    }

    private static JsonNode createStory(AuthSession session, String projectId, String suffix) {
        APIResponse response = session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", "Sample browser workflow story " + suffix,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by optional Java Playwright sample-data fixture.",
                "visibility", "workspace"
        ));
        ApiDiagnostics.writeSnippet("sample-data-work-item", "POST sample fixture work item", response);
        return session.requireJson(response, 201);
    }

    private static JsonNode createSavedFilter(AuthSession session, TestWorkspace workspace, String suffix) {
        APIResponse response = session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/saved-filters", JsonSupport.object(
                "name", "Sample Browser Filter " + suffix,
                "visibility", "project",
                "projectId", workspace.projectId(),
                "query", Map.of("projectId", workspace.projectId(), "entityType", "work_item")
        ));
        ApiDiagnostics.writeSnippet("sample-data-saved-filter", "POST sample fixture saved filter", response);
        return session.requireJson(response, 201);
    }

    private static JsonNode createDashboard(AuthSession session, TestWorkspace workspace, String suffix) {
        APIResponse response = session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/dashboards", JsonSupport.object(
                "name", "Sample Browser Dashboard " + suffix,
                "visibility", "project",
                "projectId", workspace.projectId(),
                "layout", Map.of("columns", 12)
        ));
        ApiDiagnostics.writeSnippet("sample-data-dashboard", "POST sample fixture dashboard", response);
        return session.requireJson(response, 201);
    }

    public record SampleDataContext(JsonNode workItem, JsonNode savedFilter, JsonNode dashboard) {
        static SampleDataContext empty() {
            return new SampleDataContext(null, null, null);
        }

        public boolean created() {
            return workItem != null;
        }
    }
}
