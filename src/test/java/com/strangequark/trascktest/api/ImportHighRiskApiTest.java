package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("imports")
@Tag("high-risk")
class ImportHighRiskApiTest {
    private static final String COMPLETE_OPEN_CONFLICTS_CONFIRMATION = "COMPLETE WITH OPEN CONFLICTS";
    private static final String RESOLVE_FILTERED_CONFLICTS_CONFIRMATION = "RESOLVE FILTERED CONFLICTS";

    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void importRoutesRequireAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);

            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/import-jobs"),
                    "import-jobs-unauthenticated");
            assertUnauthorized(request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/import-jobs",
                            RequestOptions.create().setData(JsonSupport.object("provider", "csv", "config", Map.of()))),
                    "import-job-create-unauthenticated");
            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/import-settings"),
                    "import-settings-unauthenticated");
            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/import-transform-presets"),
                    "import-transform-presets-unauthenticated");
            assertUnauthorized(request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/import-review/export-jobs",
                            RequestOptions.create().setData(JsonSupport.object("tableType", "workspace_completion"))),
                    "import-review-export-unauthenticated");
            request.dispose();
        }
    }

    @Test
    void authenticatedAdminsCanExerciseImportLifecycleReviewExportsAndReports() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for import API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();
            JsonNode originalSettings = session.requireJson(
                    session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/import-settings"),
                    200
            );
            List<String> createdWorkItemIds = new ArrayList<>();

            try {
                enableSampleJobsWhenAvailable(session, workspace, originalSettings);

                JsonNode preset = createTransformPreset(session, workspace.workspaceId(), "Playwright Import Preset " + suffix);
                String presetId = preset.path("id").asText();
                cleanup.delete(session, "/api/v1/import-transform-presets/" + presetId);
                JsonNode updatedPreset = session.requireJson(session.patch(
                        "/api/v1/import-transform-presets/" + presetId,
                        transformPresetRequest("Playwright Import Preset Updated " + suffix, "Updated by Playwright", true)
                ), 200);
                assertEquals("Playwright Import Preset Updated " + suffix, updatedPreset.path("name").asText(), updatedPreset.toString());

                JsonNode rowTemplate = createMappingTemplate(
                        session,
                        workspace,
                        "Playwright CSV Row Mapping " + suffix,
                        "row",
                        presetId
                );
                String rowTemplateId = rowTemplate.path("id").asText();
                JsonNode manualTemplate = createMappingTemplate(
                        session,
                        workspace,
                        "Playwright Manual Mapping " + suffix,
                        "manual",
                        presetId
                );
                String manualTemplateId = manualTemplate.path("id").asText();
                JsonNode deleteTemplate = createMappingTemplate(
                        session,
                        workspace,
                        "Playwright Delete Mapping " + suffix,
                        "discard",
                        null
                );
                assertEquals(204, session.delete("/api/v1/import-mapping-templates/" + deleteTemplate.path("id").asText()).status());

                exerciseMappingChildren(session, rowTemplateId);
                exerciseTransformPresetVersions(session, presetId, rowTemplateId);

                JsonNode job = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/import-jobs",
                        JsonSupport.object(
                                "provider", "csv",
                                "config", JsonSupport.object("targetProjectId", workspace.projectId(), "source", "playwright")
                        )
                ), 201);
                String importJobId = job.path("id").asText();
                assertEquals("queued", job.path("status").asText(), job.toString());
                assertEquals(importJobId, session.requireJson(session.get("/api/v1/import-jobs/" + importJobId), 200).path("id").asText());
                assertTrue(containsId(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/import-jobs"), 200), importJobId));

                assertEquals("running", session.requireJson(session.post("/api/v1/import-jobs/" + importJobId + "/start", Map.of()), 200).path("status").asText());
                JsonNode parsed = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/parse",
                        JsonSupport.object(
                                "contentType", "text/csv",
                                "sourceType", "row",
                                "content", csvPayload(suffix)
                        )
                ), 200);
                assertEquals(4, parsed.path("recordsParsed").asInt(), parsed.toString());

                JsonNode materialized = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/materialize",
                        JsonSupport.object(
                                "mappingTemplateId", rowTemplateId,
                                "projectId", workspace.projectId(),
                                "limit", 10,
                                "updateExisting", false
                        )
                ), 200);
                assertEquals(4, materialized.path("created").asInt(), materialized.toString());
                for (JsonNode record : materialized.path("records")) {
                    if (!record.path("targetId").asText().isBlank()) {
                        createdWorkItemIds.add(record.path("targetId").asText());
                    }
                }
                String materializationRunId = materialized.path("materializationRunId").asText();
                assertTrue(containsId(session.requireJson(session.get("/api/v1/import-jobs/" + importJobId + "/materialization-runs"), 200), materializationRunId));

                String firstTargetId = firstRow(materialized.path("records"), "materialized records").path("targetId").asText();
                JsonNode manualRecord = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/records",
                        JsonSupport.object(
                                "sourceType", "manual",
                                "sourceId", "MANUAL-" + suffix,
                                "targetType", "work_item",
                                "targetId", firstTargetId,
                                "status", "pending",
                                "rawPayload", JsonSupport.object(
                                        "title", "Manual import conflict " + suffix,
                                        "type", "Story",
                                        "status", "Open",
                                        "description", "Manual record created by Playwright.",
                                        "visibility", "Public"
                                )
                        )
                ), 201);
                assertEquals("manual", manualRecord.path("sourceType").asText(), manualRecord.toString());

                JsonNode manualConflictRun = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/materialize",
                        JsonSupport.object(
                                "mappingTemplateId", manualTemplateId,
                                "projectId", workspace.projectId(),
                                "limit", 5,
                                "updateExisting", false
                        )
                ), 200);
                assertEquals(1, manualConflictRun.path("conflicts").asInt(), manualConflictRun.toString());

                JsonNode rerun = session.requireJson(session.post(
                        "/api/v1/import-materialization-runs/" + materializationRunId + "/rerun",
                        JsonSupport.object("limit", 10, "updateExisting", false)
                ), 200);
                assertEquals(4, rerun.path("conflicts").asInt(), rerun.toString());

                APIResponse blockedComplete = session.post("/api/v1/import-jobs/" + importJobId + "/complete", Map.of());
                ApiDiagnostics.writeSnippet("import-complete-open-conflict-blocked", "POST complete with open conflicts", blockedComplete);
                assertEquals(409, blockedComplete.status(), blockedComplete.text());

                JsonNode conflicts = session.requireJson(session.get("/api/v1/import-jobs/" + importJobId + "/conflicts"), 200);
                assertTrue(conflicts.size() >= 5, conflicts.toString());
                JsonNode rowConflictOne = findBySource(conflicts, "row");
                String rowConflictOneId = rowConflictOne.path("id").asText();

                JsonNode updatedRecord = session.requireJson(session.patch(
                        "/api/v1/import-job-records/" + rowConflictOneId,
                        JsonSupport.object(
                                "status", "conflict",
                                "rawPayload", JsonSupport.object(
                                        "title", "Updated import conflict " + suffix,
                                        "type", "Story",
                                        "status", "Open",
                                        "description", "Record edited by Playwright.",
                                        "visibility", "Public"
                                )
                        )
                ), 200);
                assertEquals("Updated import conflict " + suffix, updatedRecord.path("rawPayload").path("title").asText(), updatedRecord.toString());
                assertFalse(session.requireJson(session.get("/api/v1/import-job-records/" + rowConflictOneId + "/versions"), 200).isEmpty());
                assertTrue(session.requireJson(session.get("/api/v1/import-job-records/" + rowConflictOneId + "/version-diffs"), 200).isArray());

                JsonNode resolvedOne = session.requireJson(session.post(
                        "/api/v1/import-job-records/" + rowConflictOneId + "/resolve-conflict",
                        JsonSupport.object("resolution", "skip")
                ), 200);
                assertEquals("resolved", resolvedOne.path("conflictStatus").asText(), resolvedOne.toString());

                JsonNode openRowConflicts = filterBySource(session.requireJson(session.get("/api/v1/import-jobs/" + importJobId + "/conflicts"), 200), "row");
                String selectedBulkId = firstRow(openRowConflicts, "row conflicts after single resolution").path("id").asText();
                JsonNode selectedPreview = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/conflicts/resolve-preview",
                        JsonSupport.object("scope", "selected", "recordIds", List.of(selectedBulkId), "resolution", "skip")
                ), 200);
                assertEquals(1, selectedPreview.path("matched").asInt(), selectedPreview.toString());
                JsonNode selectedResolved = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/conflicts/resolve",
                        JsonSupport.object("scope", "selected", "recordIds", List.of(selectedBulkId), "resolution", "skip")
                ), 200);
                assertEquals(1, selectedResolved.path("resolved").asInt(), selectedResolved.toString());

                JsonNode rowPreview = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/conflicts/resolve-preview",
                        filteredConflictRequest("row", null)
                ), 200);
                int rowOpenCount = rowPreview.path("matched").asInt();
                assertTrue(rowOpenCount >= 1, rowPreview.toString());
                JsonNode rowResolutionJob = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/conflicts/resolve-async",
                        filteredConflictRequest("row", rowOpenCount)
                ), 201);
                String rowResolutionJobId = rowResolutionJob.path("id").asText();
                assertEquals(rowResolutionJobId, session.requireJson(session.get("/api/v1/import-conflict-resolution-jobs/" + rowResolutionJobId), 200).path("id").asText());
                assertEquals("cancelled", session.requireJson(session.post("/api/v1/import-conflict-resolution-jobs/" + rowResolutionJobId + "/cancel", Map.of()), 200).path("status").asText());
                assertEquals("queued", session.requireJson(session.post("/api/v1/import-conflict-resolution-jobs/" + rowResolutionJobId + "/retry", Map.of()), 200).path("status").asText());
                assertEquals("completed", session.requireJson(session.post("/api/v1/import-conflict-resolution-jobs/" + rowResolutionJobId + "/run", Map.of()), 200).path("status").asText());

                JsonNode manualPreview = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/conflicts/resolve-preview",
                        filteredConflictRequest("manual", null)
                ), 200);
                int manualOpenCount = manualPreview.path("matched").asInt();
                assertEquals(1, manualOpenCount, manualPreview.toString());
                JsonNode manualResolutionJob = session.requireJson(session.post(
                        "/api/v1/import-jobs/" + importJobId + "/conflicts/resolve-async",
                        filteredConflictRequest("manual", manualOpenCount)
                ), 201);
                JsonNode workerRun = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/import-conflict-resolution-jobs/process?limit=5",
                        Map.of()
                ), 200);
                assertTrue(workerRun.path("completed").asInt() >= 1, workerRun.toString());
                assertEquals("completed", session.requireJson(session.get("/api/v1/import-conflict-resolution-jobs/" + manualResolutionJob.path("id").asText()), 200).path("status").asText());
                assertFalse(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/import-conflict-resolution-jobs?status=completed"), 200).isEmpty());
                assertFalse(session.requireJson(session.get("/api/v1/import-jobs/" + importJobId + "/conflict-resolution-jobs"), 200).isEmpty());

                JsonNode completed = session.requireJson(session.post("/api/v1/import-jobs/" + importJobId + "/complete", Map.of()), 200);
                assertEquals("completed", completed.path("status").asText(), completed.toString());

                exerciseImportExportsAndReports(session, workspace, importJobId, rowResolutionJobId);
                exerciseSampleJobs(session, workspace);

                assertEquals("failed", session.requireJson(session.post("/api/v1/import-jobs/" + createDisposableJob(session, workspace, "fail-" + suffix) + "/fail", Map.of()), 200).path("status").asText());
                assertEquals("cancelled", session.requireJson(session.post("/api/v1/import-jobs/" + createDisposableJob(session, workspace, "cancel-" + suffix) + "/cancel", Map.of()), 200).path("status").asText());
            } finally {
                for (String workItemId : createdWorkItemIds) {
                    session.delete("/api/v1/work-items/" + workItemId);
                }
                session.patch(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/import-settings",
                        JsonSupport.object("sampleJobsEnabled", originalSettings.path("sampleJobsEnabled").asBoolean(false))
                );
            }
        }
    }

    private void exerciseMappingChildren(AuthSession session, String mappingTemplateId) {
        JsonNode lookup = session.requireJson(session.post(
                "/api/v1/import-mapping-templates/" + mappingTemplateId + "/value-lookups",
                JsonSupport.object(
                        "sourceField", "visibility",
                        "sourceValue", "Public",
                        "targetField", "visibility",
                        "targetValue", "inherited",
                        "sortOrder", 0,
                        "enabled", true
                )
        ), 201);
        String lookupId = lookup.path("id").asText();
        assertFalse(session.requireJson(session.get("/api/v1/import-mapping-templates/" + mappingTemplateId + "/value-lookups"), 200).isEmpty());
        assertEquals("visibility", session.requireJson(session.patch(
                "/api/v1/import-mapping-templates/" + mappingTemplateId + "/value-lookups/" + lookupId,
                JsonSupport.object("sortOrder", 1, "enabled", true)
        ), 200).path("targetField").asText());

        JsonNode deleteLookup = session.requireJson(session.post(
                "/api/v1/import-mapping-templates/" + mappingTemplateId + "/value-lookups",
                JsonSupport.object(
                        "sourceField", "visibility",
                        "sourceValue", "Private",
                        "targetField", "visibility",
                        "targetValue", "restricted",
                        "sortOrder", 2,
                        "enabled", true
                )
        ), 201);
        assertEquals(204, session.delete("/api/v1/import-mapping-templates/" + mappingTemplateId + "/value-lookups/" + deleteLookup.path("id").asText()).status());

        JsonNode typeTranslation = session.requireJson(session.post(
                "/api/v1/import-mapping-templates/" + mappingTemplateId + "/type-translations",
                JsonSupport.object("sourceTypeKey", "Story", "targetTypeKey", "story", "enabled", true)
        ), 201);
        String typeTranslationId = typeTranslation.path("id").asText();
        assertFalse(session.requireJson(session.get("/api/v1/import-mapping-templates/" + mappingTemplateId + "/type-translations"), 200).isEmpty());
        assertEquals("story", session.requireJson(session.patch(
                "/api/v1/import-mapping-templates/" + mappingTemplateId + "/type-translations/" + typeTranslationId,
                JsonSupport.object("sourceTypeKey", "Story", "targetTypeKey", "story", "enabled", true)
        ), 200).path("targetTypeKey").asText());
        JsonNode deleteType = session.requireJson(session.post(
                "/api/v1/import-mapping-templates/" + mappingTemplateId + "/type-translations",
                JsonSupport.object("sourceTypeKey", "Task", "targetTypeKey", "story", "enabled", true)
        ), 201);
        assertEquals(204, session.delete("/api/v1/import-mapping-templates/" + mappingTemplateId + "/type-translations/" + deleteType.path("id").asText()).status());

        JsonNode statusTranslation = session.requireJson(session.post(
                "/api/v1/import-mapping-templates/" + mappingTemplateId + "/status-translations",
                JsonSupport.object("sourceStatusKey", "Open", "targetStatusKey", "open", "enabled", true)
        ), 201);
        String statusTranslationId = statusTranslation.path("id").asText();
        assertFalse(session.requireJson(session.get("/api/v1/import-mapping-templates/" + mappingTemplateId + "/status-translations"), 200).isEmpty());
        assertEquals("open", session.requireJson(session.patch(
                "/api/v1/import-mapping-templates/" + mappingTemplateId + "/status-translations/" + statusTranslationId,
                JsonSupport.object("sourceStatusKey", "Open", "targetStatusKey", "open", "enabled", true)
        ), 200).path("targetStatusKey").asText());
        JsonNode deleteStatus = session.requireJson(session.post(
                "/api/v1/import-mapping-templates/" + mappingTemplateId + "/status-translations",
                JsonSupport.object("sourceStatusKey", "Done", "targetStatusKey", "open", "enabled", true)
        ), 201);
        assertEquals(204, session.delete("/api/v1/import-mapping-templates/" + mappingTemplateId + "/status-translations/" + deleteStatus.path("id").asText()).status());
    }

    private void exerciseTransformPresetVersions(AuthSession session, String presetId, String rowTemplateId) {
        JsonNode presetDetail = session.requireJson(session.get("/api/v1/import-transform-presets/" + presetId), 200);
        assertEquals(presetId, presetDetail.path("id").asText(), presetDetail.toString());
        JsonNode versions = session.requireJson(session.get("/api/v1/import-transform-presets/" + presetId + "/versions"), 200);
        assertFalse(versions.isEmpty(), versions.toString());
        String versionId = firstRow(versions, "transform preset versions").path("id").asText();

        JsonNode clone = session.requireJson(session.post(
                "/api/v1/import-transform-presets/" + presetId + "/versions/" + versionId + "/clone",
                JsonSupport.object("name", "Playwright Clone " + UniqueData.suffix(), "description", "Cloned in API coverage", "enabled", true)
        ), 201);
        assertNotEquals(presetId, clone.path("id").asText(), clone.toString());

        JsonNode preview = session.requireJson(session.post(
                "/api/v1/import-transform-presets/" + presetId + "/versions/" + versionId + "/retarget-preview",
                JsonSupport.object("name", "Playwright Retarget Preview " + UniqueData.suffix(), "enabled", true, "mappingTemplateIds", List.of(rowTemplateId))
        ), 200);
        assertEquals(1, preview.path("templates").size(), preview.toString());
        JsonNode applied = session.requireJson(session.post(
                "/api/v1/import-transform-presets/" + presetId + "/versions/" + versionId + "/retarget",
                JsonSupport.object("name", "Playwright Retarget " + UniqueData.suffix(), "enabled", true, "mappingTemplateIds", List.of(rowTemplateId))
        ), 201);
        assertEquals(1, applied.path("templates").size(), applied.toString());
    }

    private void exerciseImportExportsAndReports(AuthSession session, TestWorkspace workspace, String importJobId, String resolutionJobId) {
        JsonNode jobDiffs = session.requireJson(session.get("/api/v1/import-jobs/" + importJobId + "/version-diffs"), 200);
        assertEquals(importJobId, jobDiffs.path("importJobId").asText(), jobDiffs.toString());
        assertEquals(importJobId, session.requireJson(session.get("/api/v1/import-jobs/" + importJobId + "/version-diffs/export"), 200).path("importJob").path("id").asText());
        JsonNode diffExportJob = session.requireJson(session.post(
                "/api/v1/import-jobs/" + importJobId + "/version-diffs/export-jobs",
                JsonSupport.object("format", "json")
        ), 201);
        assertEquals("completed", diffExportJob.path("status").asText(), diffExportJob.toString());
        APIResponse diffDownload = session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/export-jobs/" + diffExportJob.path("id").asText() + "/download");
        assertEquals(200, diffDownload.status(), diffDownload.text());
        assertTrue(diffDownload.text().contains("importJob"), diffDownload.text());

        JsonNode reviewExportJob = session.requireJson(session.post(
                "/api/v1/workspaces/" + workspace.workspaceId() + "/import-review/export-jobs",
                JsonSupport.object(
                        "tableType", "conflict_resolution_jobs",
                        "importJobId", importJobId,
                        "filterColumn", "status",
                        "filter", "completed"
                )
        ), 201);
        assertEquals("queued", reviewExportJob.path("status").asText(), reviewExportJob.toString());
        JsonNode reviewWorker = session.requireJson(session.post(
                "/api/v1/workspaces/" + workspace.workspaceId() + "/import-review/export-jobs/process",
                JsonSupport.object("limit", 5)
        ), 200);
        assertTrue(reviewWorker.path("completed").asInt() >= 1, reviewWorker.toString());
        JsonNode processedReviewExport = findByField(reviewWorker.path("jobs"), "id", reviewExportJob.path("id").asText());
        APIResponse reviewDownload = session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/export-jobs/" + processedReviewExport.path("id").asText() + "/download");
        assertEquals(200, reviewDownload.status(), reviewDownload.text());
        assertTrue(reviewDownload.text().contains("Status"), reviewDownload.text());

        JsonNode workspaceCompletionExport = session.requireJson(session.post(
                "/api/v1/workspaces/" + workspace.workspaceId() + "/import-review/export-jobs",
                JsonSupport.object("tableType", "workspace_completion")
        ), 201);
        assertEquals("queued", workspaceCompletionExport.path("status").asText(), workspaceCompletionExport.toString());

        assertTrue(session.requireJson(session.get("/api/v1/reports/projects/" + workspace.projectId() + "/imports/completions"), 200).has("completedJobs"));
        assertTrue(session.requireJson(session.get("/api/v1/reports/workspaces/" + workspace.workspaceId() + "/imports/completions"), 200).has("completedJobs"));
        assertEquals(resolutionJobId, session.requireJson(session.get("/api/v1/import-conflict-resolution-jobs/" + resolutionJobId), 200).path("id").asText());
    }

    private void exerciseSampleJobs(AuthSession session, TestWorkspace workspace) {
        APIResponse samplesResponse = session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/import-samples");
        ApiDiagnostics.writeSnippet("import-samples", "GET import samples", samplesResponse);
        if (samplesResponse.status() == 403 || samplesResponse.status() == 404) {
            return;
        }
        JsonNode samples = session.requireJson(samplesResponse, 200);
        if (samples.isEmpty()) {
            return;
        }
        String sampleKey = samples.get(0).path("key").asText("csv");
        JsonNode sampleJob = session.requireJson(session.post(
                "/api/v1/workspaces/" + workspace.workspaceId() + "/import-samples/" + sampleKey + "/jobs",
                JsonSupport.object("projectId", workspace.projectId(), "createMappingTemplate", true)
        ), 201);
        assertEquals(sampleKey, sampleJob.path("sample").path("key").asText(), sampleJob.toString());
    }

    private void enableSampleJobsWhenAvailable(AuthSession session, TestWorkspace workspace, JsonNode originalSettings) {
        JsonNode updated = session.requireJson(session.patch(
                "/api/v1/workspaces/" + workspace.workspaceId() + "/import-settings",
                JsonSupport.object("sampleJobsEnabled", true)
        ), 200);
        assertTrue(updated.has("sampleJobsEnabled"), updated.toString());
        assertTrue(originalSettings.has("workspaceId"), originalSettings.toString());
    }

    private String createDisposableJob(AuthSession session, TestWorkspace workspace, String label) {
        return session.requireJson(session.post(
                "/api/v1/workspaces/" + workspace.workspaceId() + "/import-jobs",
                JsonSupport.object("provider", "csv", "config", JsonSupport.object("targetProjectId", workspace.projectId(), "label", label))
        ), 201).path("id").asText();
    }

    private JsonNode createTransformPreset(AuthSession session, String workspaceId, String name) {
        return session.requireJson(session.post(
                "/api/v1/workspaces/" + workspaceId + "/import-transform-presets",
                transformPresetRequest(name, "Created by Playwright", true)
        ), 201);
    }

    private Map<String, Object> transformPresetRequest(String name, String description, boolean enabled) {
        return JsonSupport.object(
                "name", name,
                "description", description,
                "transformationConfig", JsonSupport.object("title", List.of("trim", "collapse_whitespace")),
                "enabled", enabled
        );
    }

    private JsonNode createMappingTemplate(
            AuthSession session,
            TestWorkspace workspace,
            String name,
            String sourceType,
            String transformPresetId
    ) {
        return session.requireJson(session.post(
                "/api/v1/workspaces/" + workspace.workspaceId() + "/import-mapping-templates",
                mappingTemplateRequest(name, workspace.projectId(), sourceType, transformPresetId)
        ), 201);
    }

    private Map<String, Object> mappingTemplateRequest(String name, String projectId, String sourceType, String transformPresetId) {
        return JsonSupport.object(
                "name", name,
                "provider", "csv",
                "sourceType", sourceType,
                "targetType", "work_item",
                "projectId", projectId,
                "workItemTypeKey", "story",
                "statusKey", "open",
                "transformPresetId", transformPresetId,
                "fieldMapping", JsonSupport.object(
                        "title", "title",
                        "typeKey", "type",
                        "statusKey", "status",
                        "descriptionMarkdown", "description",
                        "visibility", "visibility"
                ),
                "defaults", JsonSupport.object("visibility", "inherited"),
                "transformationConfig", JsonSupport.object("title", List.of("trim", "collapse_whitespace")),
                "enabled", true
        );
    }

    private Map<String, Object> filteredConflictRequest(String sourceType, Integer expectedCount) {
        return JsonSupport.object(
                "scope", "filtered",
                "sourceType", sourceType,
                "conflictStatus", "open",
                "resolution", "skip",
                "expectedCount", expectedCount,
                "confirmation", expectedCount == null ? null : RESOLVE_FILTERED_CONFLICTS_CONFIRMATION
        );
    }

    private String csvPayload(String suffix) {
        return """
                id,title,type,status,description,visibility
                CSV-%s-1, Imported story one %s ,Story,Open,Created by Playwright one,Public
                CSV-%s-2,Imported story two %s,Story,Open,Created by Playwright two,Public
                CSV-%s-3,Imported story three %s,Story,Open,Created by Playwright three,Public
                CSV-%s-4,Imported story four %s,Story,Open,Created by Playwright four,Public
                """.formatted(suffix, suffix, suffix, suffix, suffix, suffix, suffix, suffix);
    }

    private JsonNode filterBySource(JsonNode rows, String sourceType) {
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode row : rows) {
            if (sourceType.equals(row.path("sourceType").asText())) {
                matches.add(row);
            }
        }
        return JsonSupport.read(matches.toString());
    }

    private JsonNode findBySource(JsonNode rows, String sourceType) {
        for (JsonNode row : rows) {
            if (sourceType.equals(row.path("sourceType").asText())) {
                return row;
            }
        }
        throw new AssertionError("No row with sourceType " + sourceType + " in " + rows);
    }

    private JsonNode findByField(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("No row with " + field + "=" + value + " in " + rows);
    }

    private JsonNode firstRow(JsonNode rows, String label) {
        assertTrue(rows.isArray() && !rows.isEmpty(), "Expected at least one " + label + ": " + rows);
        return rows.get(0);
    }

    private boolean containsId(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    private void assertUnauthorized(APIResponse response, String snippetName) {
        ApiDiagnostics.writeSnippet(snippetName, snippetName, response);
        assertTrue(response.status() == 401 || response.status() == 403, response.text());
    }
}
