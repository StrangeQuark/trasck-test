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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("contract")
class OpenApiRouteInventoryApiTest {
    private static final String COVERAGE_BASELINE = "/backend-route-coverage.tsv";
    private static final String BASELINE_HEADER = "status\tmethod\tpath\ttags\toperationId\tcoverageOwner";
    private static final Set<String> VALID_STATUSES = Set.of("covered", "planned-high-risk", "planned");

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

            Map<String, CoverageEntry> baseline = readCoverageBaseline();
            assertBaselineMatchesOpenApi(operations, baseline);

            String inventory = inventoryText(operations, baseline);
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

    private String inventoryText(List<RouteOperation> operations, Map<String, CoverageEntry> baseline) {
        StringBuilder builder = new StringBuilder(BASELINE_HEADER).append('\n');
        for (RouteOperation operation : operations) {
            CoverageEntry coverage = baseline.get(operation.key());
            builder.append(coverage == null ? "missing" : coverage.status())
                    .append('\t')
                    .append(operation.method())
                    .append('\t')
                    .append(operation.path())
                    .append('\t')
                    .append(String.join(",", operation.tags()))
                    .append('\t')
                    .append(operation.operationId())
                    .append('\t')
                    .append(coverage == null ? "" : coverage.coverageOwner())
                    .append('\n');
        }
        return builder.toString();
    }

    private Map<String, CoverageEntry> readCoverageBaseline() {
        InputStream stream = OpenApiRouteInventoryApiTest.class.getResourceAsStream(COVERAGE_BASELINE);
        if (stream == null) {
            throw new AssertionError("Missing committed route coverage baseline " + COVERAGE_BASELINE);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
            if (lines.isEmpty() || !BASELINE_HEADER.equals(lines.getFirst())) {
                throw new AssertionError("Route coverage baseline must start with: " + BASELINE_HEADER.replace("\t", "\\t"));
            }
            java.util.LinkedHashMap<String, CoverageEntry> entries = new java.util.LinkedHashMap<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] columns = line.split("\t", -1);
                if (columns.length != 5 && columns.length != 6) {
                    throw new AssertionError("Invalid route coverage baseline row: " + line);
                }
                CoverageEntry entry = new CoverageEntry(
                        columns[0],
                        columns[1],
                        columns[2],
                        columns[3],
                        columns[4],
                        columns.length == 6 ? columns[5] : ""
                );
                if (!VALID_STATUSES.contains(entry.status())) {
                    throw new AssertionError("Invalid route coverage status '" + entry.status() + "' for " + entry.key());
                }
                if ("covered".equals(entry.status()) && entry.coverageOwner().isBlank()) {
                    throw new AssertionError("Covered route must include coverageOwner for " + entry.key());
                }
                if (!"covered".equals(entry.status()) && !entry.coverageOwner().isBlank()) {
                    throw new AssertionError("Only covered routes may include coverageOwner for " + entry.key());
                }
                if (entries.put(entry.key(), entry) != null) {
                    throw new AssertionError("Duplicate route coverage baseline row for " + entry.key());
                }
            }
            return entries;
        } catch (IOException ex) {
            throw new AssertionError("Unable to read route coverage baseline " + COVERAGE_BASELINE, ex);
        }
    }

    private void assertBaselineMatchesOpenApi(List<RouteOperation> operations, Map<String, CoverageEntry> baseline) {
        Set<String> runtimeKeys = operations.stream()
                .map(RouteOperation::key)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> baselineKeys = new TreeSet<>(baseline.keySet());

        Set<String> missing = new TreeSet<>(runtimeKeys);
        missing.removeAll(baselineKeys);
        Set<String> stale = new TreeSet<>(baselineKeys);
        stale.removeAll(runtimeKeys);

        assertTrue(missing.isEmpty(),
                "Add these routes to src/test/resources/backend-route-coverage.tsv with an explicit status: " + missing);
        assertTrue(stale.isEmpty(),
                "Remove or update stale routes in src/test/resources/backend-route-coverage.tsv: " + stale);
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

    private record CoverageEntry(String status, String method, String path, String tags, String operationId, String coverageOwner) {
        String key() {
            return method + " " + path;
        }
    }
}
