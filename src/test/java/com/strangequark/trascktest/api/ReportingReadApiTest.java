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
@Tag("reporting")
class ReportingReadApiTest {
    private static final String SNAPSHOT_DATE = "2026-04-21";

    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void authenticatedUsersCanRunAndReadReportingSnapshotsAndSummaries() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for reporting API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();

            JsonNode team = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/teams", JsonSupport.object(
                    "name", "Playwright Reporting Team " + suffix,
                    "description", "Created by reporting Playwright API coverage",
                    "leadUserId", session.userId(),
                    "defaultCapacity", 80
            )), 201);
            String teamId = team.path("id").asText();
            cleanup.delete(session, "/api/v1/teams/" + teamId);
            session.requireJson(session.put("/api/v1/projects/" + workspace.projectId() + "/teams/" + teamId, JsonSupport.object(
                    "role", "delivery"
            )), 200);
            cleanup.delete(session, "/api/v1/projects/" + workspace.projectId() + "/teams/" + teamId);

            JsonNode iteration = session.requireJson(session.post("/api/v1/projects/" + workspace.projectId() + "/iterations", JsonSupport.object(
                    "name", "Playwright Reporting Sprint " + suffix,
                    "teamId", teamId,
                    "startDate", "2026-04-20",
                    "endDate", "2026-04-28",
                    "committedPoints", 5
            )), 201);
            String iterationId = iteration.path("id").asText();
            cleanup.delete(session, "/api/v1/iterations/" + iterationId);

            JsonNode workItem = createStory(session, workspace.projectId(), "Playwright reporting story " + suffix);
            String workItemId = workItem.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + workItemId);
            session.requireJson(session.post("/api/v1/work-items/" + workItemId + "/assign", JsonSupport.object(
                    "assigneeId", session.userId()
            )), 200);
            session.requireJson(session.patch("/api/v1/work-items/" + workItemId, JsonSupport.object(
                    "estimatePoints", 5,
                    "estimateMinutes", 300,
                    "remainingMinutes", 180
            )), 200);
            session.requireJson(session.post("/api/v1/work-items/" + workItemId + "/team", JsonSupport.object(
                    "teamId", teamId
            )), 200);
            JsonNode workLog = session.requireJson(session.post("/api/v1/work-items/" + workItemId + "/work-logs", JsonSupport.object(
                    "userId", session.userId(),
                    "minutesSpent", 45,
                    "workDate", SNAPSHOT_DATE,
                    "startedAt", SNAPSHOT_DATE + "T14:00:00Z",
                    "descriptionMarkdown", "Playwright reporting coverage."
            )), 201);
            cleanup.delete(session, "/api/v1/work-items/" + workItemId + "/work-logs/" + workLog.path("id").asText());
            session.requireJson(session.post("/api/v1/iterations/" + iterationId + "/work-items", JsonSupport.object(
                    "workItemId", workItemId
            )), 201);
            cleanup.delete(session, "/api/v1/iterations/" + iterationId + "/work-items/" + workItemId);

            assertTrue(session.requireJson(session.get("/api/v1/reports/work-items/" + workItemId + "/status-history"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/reports/work-items/" + workItemId + "/assignment-history"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/reports/work-items/" + workItemId + "/estimate-history"), 200).isArray());
            assertTrue(session.requireJson(session.get("/api/v1/reports/work-items/" + workItemId + "/team-history"), 200).isArray());
            JsonNode workLogSummary = session.requireJson(session.get("/api/v1/reports/work-items/" + workItemId + "/work-log-summary"), 200);
            assertEquals(45, workLogSummary.path("totalMinutes").asLong(), workLogSummary.toString());

            JsonNode projectSummary = session.requireJson(session.get("/api/v1/reports/projects/" + workspace.projectId() + "/dashboard-summary?teamId=" + teamId + "&iterationId=" + iterationId), 200);
            assertEquals(workspace.projectId(), projectSummary.path("projectId").asText(), projectSummary.toString());
            JsonNode workspaceSummary = session.requireJson(session.get("/api/v1/reports/workspaces/" + workspace.workspaceId() + "/dashboard-summary?projectIds=" + workspace.projectId()), 200);
            assertEquals(workspace.workspaceId(), workspaceSummary.path("workspaceId").asText(), workspaceSummary.toString());

            JsonNode program = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/programs", JsonSupport.object(
                    "name", "Playwright Program " + suffix,
                    "description", "Created by reporting Playwright API coverage",
                    "roadmapConfig", Map.of("view", "timeline", "horizon", "quarter"),
                    "reportConfig", Map.of("defaultWindow", "last_30_days", "showImportCompletion", true)
            )), 201);
            String programId = program.path("id").asText();
            cleanup.delete(session, "/api/v1/programs/" + programId);
            assertEquals("timeline", program.path("roadmapConfig").path("view").asText(), program.toString());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/programs"), 200).isArray());
            JsonNode updatedProgram = session.requireJson(session.patch("/api/v1/programs/" + programId, JsonSupport.object(
                    "description", "Updated by reporting Playwright API coverage",
                    "reportConfig", Map.of("defaultWindow", "current_quarter", "showImportCompletion", true)
            )), 200);
            assertEquals("current_quarter", updatedProgram.path("reportConfig").path("defaultWindow").asText(), updatedProgram.toString());
            JsonNode fetchedProgram = session.requireJson(session.get("/api/v1/programs/" + programId), 200);
            assertEquals(programId, fetchedProgram.path("id").asText(), fetchedProgram.toString());
            JsonNode assignedProject = session.requireJson(session.put("/api/v1/programs/" + programId + "/projects/" + workspace.projectId(), JsonSupport.object(
                    "position", 1
            )), 200);
            cleanup.delete(session, "/api/v1/programs/" + programId + "/projects/" + workspace.projectId());
            assertEquals(workspace.projectId(), assignedProject.path("projectId").asText(), assignedProject.toString());
            JsonNode programProjects = session.requireJson(session.get("/api/v1/programs/" + programId + "/projects"), 200);
            assertEquals(1, programProjects.size(), programProjects.toString());
            JsonNode programSummary = session.requireJson(session.get("/api/v1/reports/programs/" + programId + "/dashboard-summary"), 200);
            assertEquals("program", programSummary.path("scope").path("scopeType").asText(), programSummary.toString());
            assertEquals(programId, programSummary.path("scope").path("programId").asText(), programSummary.toString());
            assertEquals(204, session.delete("/api/v1/programs/" + programId + "/projects/" + workspace.projectId()).status());
            assertEquals(204, session.delete("/api/v1/programs/" + programId).status());

            JsonNode snapshotRun = session.requireJson(session.post("/api/v1/reports/workspaces/" + workspace.workspaceId() + "/snapshots/run?date=" + SNAPSHOT_DATE, Map.of()), 200);
            assertEquals(SNAPSHOT_DATE, snapshotRun.path("snapshotDate").asText(), snapshotRun.toString());
            JsonNode backfill = session.requireJson(session.post("/api/v1/reports/workspaces/" + workspace.workspaceId() + "/snapshots/backfill?fromDate=" + SNAPSHOT_DATE + "&toDate=" + SNAPSHOT_DATE, Map.of()), 200);
            assertEquals(1, backfill.path("daysProcessed").asInt(), backfill.toString());
            JsonNode reconcile = session.requireJson(session.post("/api/v1/reports/workspaces/" + workspace.workspaceId() + "/snapshots/reconcile?fromDate=" + SNAPSHOT_DATE + "&toDate=" + SNAPSHOT_DATE, Map.of()), 200);
            assertEquals("reconcile", reconcile.path("action").asText(), reconcile.toString());
            JsonNode rollups = session.requireJson(session.post("/api/v1/reports/workspaces/" + workspace.workspaceId() + "/snapshots/rollups/run?fromDate=" + SNAPSHOT_DATE + "&toDate=" + SNAPSHOT_DATE + "&granularity=weekly", Map.of()), 200);
            assertEquals("weekly", rollups.path("granularity").asText(), rollups.toString());
            JsonNode rollupBackfill = session.requireJson(session.post("/api/v1/reports/workspaces/" + workspace.workspaceId() + "/snapshots/rollups/backfill?fromDate=" + SNAPSHOT_DATE + "&toDate=" + SNAPSHOT_DATE + "&granularity=weekly", Map.of()), 200);
            assertEquals("rollup_backfill", rollupBackfill.path("action").asText(), rollupBackfill.toString());

            JsonNode projectSnapshots = session.requireJson(session.get("/api/v1/reports/projects/" + workspace.projectId() + "/snapshots?fromDate=" + SNAPSHOT_DATE + "&toDate=" + SNAPSHOT_DATE), 200);
            assertTrue(projectSnapshots.path("series").isArray(), projectSnapshots.toString());
            JsonNode iterationReport = session.requireJson(session.get("/api/v1/reports/iterations/" + iterationId + "/report?source=latest"), 200);
            assertEquals(iterationId, iterationReport.path("iterationId").asText(), iterationReport.toString());
        }
    }

    private JsonNode createStory(AuthSession session, String projectId, String title) {
        APIResponse response = session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", title,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by reporting Playwright API coverage.",
                "visibility", "inherited"
        ));
        ApiDiagnostics.writeSnippet("reporting-work-item-create", "POST project work item", response);
        return session.requireJson(response, 201);
    }
}
