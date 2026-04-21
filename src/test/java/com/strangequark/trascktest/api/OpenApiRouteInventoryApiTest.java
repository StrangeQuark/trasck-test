package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.ArtifactPaths;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("contract")
class OpenApiRouteInventoryApiTest {
    private static final Set<String> COVERED_ROUTE_KEYS = Set.of(
            "GET /api/trasck/health",
            "POST /api/v1/setup",
            "GET /api/v1/auth/csrf",
            "POST /api/v1/auth/login",
            "GET /api/v1/auth/me",
            "POST /api/v1/auth/tokens/personal",
            "GET /api/v1/auth/tokens/personal",
            "DELETE /api/v1/auth/tokens/{tokenId}",
            "POST /api/v1/workspaces/{workspaceId}/service-tokens",
            "GET /api/v1/workspaces/{workspaceId}/service-tokens",
            "DELETE /api/v1/workspaces/{workspaceId}/service-tokens/{tokenId}",
            "GET /api/v1/system-admins",
            "GET /api/v1/workspaces/{workspaceId}/security-policy",
            "PATCH /api/v1/workspaces/{workspaceId}/security-policy",
            "GET /api/v1/projects/{projectId}/security-policy",
            "PATCH /api/v1/projects/{projectId}/security-policy",
            "POST /api/v1/projects/{projectId}/work-items",
            "GET /api/v1/projects/{projectId}/work-items",
            "PATCH /api/v1/work-items/{workItemId}",
            "DELETE /api/v1/work-items/{workItemId}",
            "GET /api/v1/work-items/{workItemId}/comments",
            "POST /api/v1/work-items/{workItemId}/comments",
            "DELETE /api/v1/work-items/{workItemId}/comments/{commentId}",
            "POST /api/v1/work-items/{workItemId}/links",
            "DELETE /api/v1/work-items/{workItemId}/links/{linkId}",
            "POST /api/v1/work-items/{workItemId}/watchers",
            "DELETE /api/v1/work-items/{workItemId}/watchers/{userId}",
            "POST /api/v1/work-items/{workItemId}/work-logs",
            "DELETE /api/v1/work-items/{workItemId}/work-logs/{workLogId}",
            "POST /api/v1/work-items/{workItemId}/attachments",
            "DELETE /api/v1/work-items/{workItemId}/attachments/{attachmentId}",
            "GET /api/v1/workspaces/{workspaceId}/teams",
            "POST /api/v1/workspaces/{workspaceId}/teams",
            "DELETE /api/v1/teams/{teamId}",
            "GET /api/v1/projects/{projectId}/teams",
            "PUT /api/v1/projects/{projectId}/teams/{teamId}",
            "DELETE /api/v1/projects/{projectId}/teams/{teamId}",
            "GET /api/v1/projects/{projectId}/iterations",
            "POST /api/v1/projects/{projectId}/iterations",
            "DELETE /api/v1/iterations/{iterationId}",
            "GET /api/v1/projects/{projectId}/releases",
            "POST /api/v1/projects/{projectId}/releases",
            "DELETE /api/v1/releases/{releaseId}",
            "GET /api/v1/projects/{projectId}/roadmaps",
            "POST /api/v1/workspaces/{workspaceId}/roadmaps",
            "DELETE /api/v1/roadmaps/{roadmapId}",
            "GET /api/v1/projects/{projectId}/boards",
            "POST /api/v1/projects/{projectId}/boards",
            "DELETE /api/v1/boards/{boardId}",
            "GET /api/v1/boards/{boardId}/columns",
            "POST /api/v1/boards/{boardId}/columns",
            "DELETE /api/v1/boards/{boardId}/columns/{columnId}",
            "GET /api/v1/boards/{boardId}/work-items",
            "GET /api/v1/projects/{projectId}/saved-filters",
            "POST /api/v1/workspaces/{workspaceId}/saved-filters",
            "GET /api/v1/saved-filters/{savedFilterId}/work-items",
            "DELETE /api/v1/saved-filters/{savedFilterId}",
            "GET /api/v1/projects/{projectId}/dashboards",
            "POST /api/v1/workspaces/{workspaceId}/dashboards",
            "GET /api/v1/dashboards/{dashboardId}/render",
            "DELETE /api/v1/dashboards/{dashboardId}",
            "POST /api/v1/dashboards/{dashboardId}/widgets",
            "DELETE /api/v1/dashboards/{dashboardId}/widgets/{widgetId}",
            "POST /api/v1/workspaces/{workspaceId}/personalization/views",
            "DELETE /api/v1/personalization/views/{viewId}",
            "POST /api/v1/workspaces/{workspaceId}/personalization/favorites",
            "DELETE /api/v1/personalization/favorites/{favoriteId}",
            "POST /api/v1/workspaces/{workspaceId}/personalization/recent-items",
            "DELETE /api/v1/personalization/recent-items/{recentItemId}",
            "GET /api/v1/workspaces/{workspaceId}/audit-log",
            "GET /api/v1/workspaces/{workspaceId}/audit-retention-policy",
            "GET /api/v1/workspaces/{workspaceId}/export-jobs"
    );

    private static final Set<String> HIGH_RISK_MARKERS = Set.of(
            "/auth/",
            "/tokens",
            "/service-tokens",
            "/system-admins",
            "/audit",
            "/export",
            "/imports",
            "/automation",
            "/webhook",
            "/email",
            "/agent",
            "/domain-events"
    );

    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void generatedOpenApiRouteInventoryIsWrittenForCoveragePlanning() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);
            APIResponse response = request.get("/v3/api-docs");
            ApiDiagnostics.writeSnippet("openapi-route-inventory-source", "GET /v3/api-docs", response);
            assertTrue(response.status() == 200, response.text());

            JsonNode openApi = JsonSupport.read(response.text());
            List<RouteOperation> operations = routeOperations(openApi);
            assertTrue(operations.size() > 100, "Expected a substantial backend route surface, got " + operations.size());
            assertTrue(operations.stream().anyMatch(operation -> "POST /api/v1/setup".equals(operation.key())),
                    "Setup route should be present in OpenAPI inventory");
            assertTrue(operations.stream().anyMatch(operation -> operation.path().contains("/agent")),
                    "Agent routes should be present in OpenAPI inventory");

            String inventory = inventoryText(operations);
            assertFalse(inventory.isBlank());
            writeInventory(inventory);
            request.dispose();
        }
    }

    private List<RouteOperation> routeOperations(JsonNode openApi) {
        List<RouteOperation> operations = new ArrayList<>();
        JsonNode paths = openApi.path("paths");
        paths.fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
            String method = methodEntry.getKey().toUpperCase(Locale.ROOT);
            if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                return;
            }
            JsonNode operation = methodEntry.getValue();
            operations.add(new RouteOperation(
                    method,
                    pathEntry.getKey(),
                    operation.path("operationId").asText(""),
                    tags(operation.path("tags"))
            ));
        }));
        operations.sort(Comparator.comparing(RouteOperation::path).thenComparing(RouteOperation::method));
        return operations;
    }

    private Set<String> tags(JsonNode tags) {
        Set<String> values = new LinkedHashSet<>();
        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                if (tag.isTextual() && !tag.asText().isBlank()) {
                    values.add(tag.asText());
                }
            }
        }
        return values;
    }

    private String inventoryText(List<RouteOperation> operations) {
        StringBuilder builder = new StringBuilder("status\tmethod\tpath\ttags\toperationId\n");
        for (RouteOperation operation : operations) {
            builder.append(status(operation))
                    .append('\t')
                    .append(operation.method())
                    .append('\t')
                    .append(operation.path())
                    .append('\t')
                    .append(String.join(",", operation.tags()))
                    .append('\t')
                    .append(operation.operationId())
                    .append('\n');
        }
        return builder.toString();
    }

    private String status(RouteOperation operation) {
        if (COVERED_ROUTE_KEYS.contains(operation.key())) {
            return "covered";
        }
        if (HIGH_RISK_MARKERS.stream().anyMatch(marker -> operation.path().contains(marker))) {
            return "planned-high-risk";
        }
        return "planned";
    }

    private void writeInventory(String inventory) {
        Path path = ArtifactPaths.apiArtifact("backend-route-inventory.tsv");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, inventory);
        } catch (IOException ex) {
            throw new AssertionError("Unable to write OpenAPI route inventory to " + path, ex);
        }
    }

    private record RouteOperation(String method, String path, String operationId, Set<String> tags) {
        String key() {
            return method + " " + path;
        }
    }
}
