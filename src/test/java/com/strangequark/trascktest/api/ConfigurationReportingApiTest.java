package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("configuration")
@Tag("reporting")
class ConfigurationReportingApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void configurationAndReportingCrudSurfacesRequireAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);

            APIResponse fields = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/custom-fields");
            ApiDiagnostics.writeSnippet("custom-fields-unauthenticated", "GET workspace custom fields without auth", fields);
            assertTrue(fields.status() == 401 || fields.status() == 403, fields.text());

            APIResponse screens = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/screens");
            ApiDiagnostics.writeSnippet("screens-unauthenticated", "GET workspace screens without auth", screens);
            assertTrue(screens.status() == 401 || screens.status() == 403, screens.text());

            APIResponse reportCatalog = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/report-query-catalog");
            ApiDiagnostics.writeSnippet("report-query-catalog-unauthenticated", "GET report query catalog without auth", reportCatalog);
            assertTrue(reportCatalog.status() == 401 || reportCatalog.status() == 403, reportCatalog.text());
            request.dispose();
        }
    }

    @Test
    void authenticatedAdminsCanManageCustomFieldsScreensAndReportCatalogEntries() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for configuration/reporting API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();

            JsonNode customField = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/custom-fields", JsonSupport.object(
                    "name", "Playwright Text " + suffix,
                    "key", "pw_text_" + suffix,
                    "fieldType", "text",
                    "options", Map.of("maxLength", 80),
                    "searchable", true
            )), 201);
            String customFieldId = customField.path("id").asText();
            cleanup.delete(session, "/api/v1/custom-fields/" + customFieldId);
            assertEquals("text", customField.path("fieldType").asText(), customField.toString());
            assertEquals(customFieldId, session.requireJson(session.get("/api/v1/custom-fields/" + customFieldId), 200).path("id").asText());
            JsonNode updatedCustomField = session.requireJson(session.patch("/api/v1/custom-fields/" + customFieldId, JsonSupport.object(
                    "name", "Playwright Text Updated " + suffix
            )), 200);
            assertEquals("Playwright Text Updated " + suffix, updatedCustomField.path("name").asText(), updatedCustomField.toString());
            assertFalse(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/custom-fields"), 200).isEmpty());

            JsonNode context = session.requireJson(session.post("/api/v1/custom-fields/" + customFieldId + "/contexts", JsonSupport.object(
                    "projectId", workspace.projectId(),
                    "required", false,
                    "defaultValue", "unset",
                    "validationConfig", Map.of("maxLength", 80)
            )), 201);
            String contextId = context.path("id").asText();
            cleanup.delete(session, "/api/v1/custom-fields/" + customFieldId + "/contexts/" + contextId);
            assertEquals(workspace.projectId(), context.path("projectId").asText(), context.toString());
            assertTrue(session.requireJson(session.get("/api/v1/custom-fields/" + customFieldId + "/contexts"), 200).isArray());
            JsonNode updatedContext = session.requireJson(session.patch("/api/v1/custom-fields/" + customFieldId + "/contexts/" + contextId, JsonSupport.object(
                    "defaultValue", "changed"
            )), 200);
            assertEquals("changed", updatedContext.path("defaultValue").asText(), updatedContext.toString());

            JsonNode fieldConfiguration = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/field-configurations", JsonSupport.object(
                    "customFieldId", customFieldId,
                    "projectId", workspace.projectId(),
                    "required", false,
                    "hidden", false,
                    "defaultValue", "unset",
                    "validationConfig", Map.of("maxLength", 80)
            )), 201);
            String fieldConfigurationId = fieldConfiguration.path("id").asText();
            cleanup.delete(session, "/api/v1/field-configurations/" + fieldConfigurationId);
            assertEquals(customFieldId, fieldConfiguration.path("customFieldId").asText(), fieldConfiguration.toString());
            assertEquals(fieldConfigurationId, session.requireJson(session.get("/api/v1/field-configurations/" + fieldConfigurationId), 200).path("id").asText());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/field-configurations"), 200).isArray());
            JsonNode updatedFieldConfiguration = session.requireJson(session.patch("/api/v1/field-configurations/" + fieldConfigurationId, JsonSupport.object(
                    "defaultValue", "changed"
            )), 200);
            assertEquals("changed", updatedFieldConfiguration.path("defaultValue").asText(), updatedFieldConfiguration.toString());
            assertTrue(session.requireJson(session.get("/api/v1/custom-fields/" + customFieldId + "/field-configurations"), 200).isArray());

            JsonNode screen = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/screens", JsonSupport.object(
                    "name", "Playwright Screen " + suffix,
                    "screenType", "work_item",
                    "config", Map.of("source", "playwright")
            )), 201);
            String screenId = screen.path("id").asText();
            cleanup.delete(session, "/api/v1/screens/" + screenId);
            assertEquals(screenId, session.requireJson(session.get("/api/v1/screens/" + screenId), 200).path("id").asText());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/screens"), 200).isArray());
            JsonNode updatedScreen = session.requireJson(session.patch("/api/v1/screens/" + screenId, JsonSupport.object(
                    "name", "Playwright Screen Updated " + suffix
            )), 200);
            assertEquals("Playwright Screen Updated " + suffix, updatedScreen.path("name").asText(), updatedScreen.toString());

            JsonNode screenField = session.requireJson(session.post("/api/v1/screens/" + screenId + "/fields", JsonSupport.object(
                    "customFieldId", customFieldId,
                    "position", 1,
                    "required", false
            )), 201);
            String screenFieldId = screenField.path("id").asText();
            cleanup.delete(session, "/api/v1/screens/" + screenId + "/fields/" + screenFieldId);
            assertEquals(customFieldId, screenField.path("customFieldId").asText(), screenField.toString());
            JsonNode updatedScreenField = session.requireJson(session.patch("/api/v1/screens/" + screenId + "/fields/" + screenFieldId, JsonSupport.object(
                    "position", 2
            )), 200);
            assertEquals(2, updatedScreenField.path("position").asInt(), updatedScreenField.toString());
            assertTrue(session.requireJson(session.get("/api/v1/screens/" + screenId + "/fields"), 200).isArray());

            JsonNode assignment = session.requireJson(session.post("/api/v1/screens/" + screenId + "/assignments", JsonSupport.object(
                    "projectId", workspace.projectId(),
                    "operation", "view",
                    "priority", 10
            )), 201);
            String assignmentId = assignment.path("id").asText();
            cleanup.delete(session, "/api/v1/screens/" + screenId + "/assignments/" + assignmentId);
            assertEquals("view", assignment.path("operation").asText(), assignment.toString());
            JsonNode updatedAssignment = session.requireJson(session.patch("/api/v1/screens/" + screenId + "/assignments/" + assignmentId, JsonSupport.object(
                    "priority", 11
            )), 200);
            assertEquals(11, updatedAssignment.path("priority").asInt(), updatedAssignment.toString());
            assertTrue(session.requireJson(session.get("/api/v1/screens/" + screenId + "/assignments"), 200).isArray());

            JsonNode workItem = createStory(session, workspace.projectId(), "Playwright custom field story " + suffix);
            String workItemId = workItem.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + workItemId);
            JsonNode value = session.requireJson(session.put("/api/v1/work-items/" + workItemId + "/custom-fields/" + customFieldId, JsonSupport.object(
                    "value", "configured-" + suffix
            )), 200);
            cleanup.delete(session, "/api/v1/work-items/" + workItemId + "/custom-fields/" + customFieldId);
            assertEquals("configured-" + suffix, value.path("value").asText(), value.toString());
            assertTrue(session.requireJson(session.get("/api/v1/work-items/" + workItemId + "/custom-fields"), 200).isArray());

            JsonNode query = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/report-query-catalog", JsonSupport.object(
                    "queryKey", "pw_project_summary_" + suffix,
                    "name", "Playwright Project Summary " + suffix,
                    "description", "Created by Java Playwright configuration/reporting coverage",
                    "queryType", "project_dashboard_summary",
                    "queryConfig", Map.of("projectId", workspace.projectId()),
                    "parametersSchema", Map.of("type", "object", "additionalProperties", false),
                    "visibility", "project",
                    "projectId", workspace.projectId(),
                    "enabled", true
            )), 201);
            String queryId = query.path("id").asText();
            cleanup.delete(session, "/api/v1/report-query-catalog/" + queryId);
            assertEquals("project_dashboard_summary", query.path("queryType").asText(), query.toString());
            assertEquals(queryId, session.requireJson(session.get("/api/v1/report-query-catalog/" + queryId), 200).path("id").asText());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/report-query-catalog"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/report-query-catalog"), 200).isArray());
            JsonNode updatedQuery = session.requireJson(session.patch("/api/v1/report-query-catalog/" + queryId, JsonSupport.object(
                    "name", "Playwright Project Summary Updated " + suffix,
                    "enabled", false
            )), 200);
            assertFalse(updatedQuery.path("enabled").asBoolean(), updatedQuery.toString());

            JsonNode retention = session.requireJson(session.get("/api/v1/reports/workspaces/" + workspace.workspaceId() + "/snapshot-retention-policy"), 200);
            JsonNode updatedRetention = session.requireJson(session.put("/api/v1/reports/workspaces/" + workspace.workspaceId() + "/snapshot-retention-policy", JsonSupport.object(
                    "rawRetentionDays", retention.path("rawRetentionDays").asInt(),
                    "weeklyRollupAfterDays", retention.path("weeklyRollupAfterDays").asInt(),
                    "monthlyRollupAfterDays", retention.path("monthlyRollupAfterDays").asInt(),
                    "archiveAfterDays", retention.path("archiveAfterDays").asInt(),
                    "destructivePruningEnabled", retention.path("destructivePruningEnabled").asBoolean()
            )), 200);
            assertEquals(workspace.workspaceId(), updatedRetention.path("workspaceId").asText(), updatedRetention.toString());
        }
    }

    private JsonNode createStory(AuthSession session, String projectId, String title) {
        APIResponse response = session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", title,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by configuration/reporting Playwright API coverage.",
                "visibility", "workspace"
        ));
        ApiDiagnostics.writeSnippet("configuration-reporting-work-item-create", "POST project work item", response);
        return session.requireJson(response, 201);
    }
}
