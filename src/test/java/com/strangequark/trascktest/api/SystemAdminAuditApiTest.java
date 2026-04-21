package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("security")
@Tag("audit")
class SystemAdminAuditApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void systemAdminAndAuditSurfacesRequireAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);

            assertUnauthorized(request.get("/api/v1/system-admins"), "system-admins-unauthenticated");
            assertUnauthorized(request.post("/api/v1/system-admins",
                    RequestOptions.create().setData(JsonSupport.object("userId", "00000000-0000-0000-0000-000000000001"))),
                    "grant-system-admin-unauthenticated");
            assertUnauthorized(request.delete("/api/v1/system-admins/00000000-0000-0000-0000-000000000001"),
                    "revoke-system-admin-unauthenticated");
            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/audit-log?limit=5"),
                    "audit-log-unauthenticated");
            assertUnauthorized(request.put("/api/v1/workspaces/" + workspace.workspaceId() + "/audit-retention-policy",
                    RequestOptions.create().setData(JsonSupport.object("retentionEnabled", false))),
                    "audit-retention-policy-update-unauthenticated");
            assertUnauthorized(request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/audit-retention-policy/export"),
                    "audit-retention-export-unauthenticated");
            assertUnauthorized(request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/audit-retention-policy/prune"),
                    "audit-retention-prune-unauthenticated");
            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/export-jobs?limit=5"),
                    "export-jobs-unauthenticated");
            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId()
                    + "/export-jobs/00000000-0000-0000-0000-000000000001"),
                    "export-job-get-unauthenticated");
            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId()
                    + "/export-jobs/00000000-0000-0000-0000-000000000001/download"),
                    "export-job-download-unauthenticated");
            assertUnauthorized(request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/domain-events/replay",
                    RequestOptions.create().setData(JsonSupport.object("includePublished", false))),
                    "domain-events-replay-unauthenticated");
            request.dispose();
        }
    }

    @Test
    void authenticatedAdminCanListSystemAdminsAuditLogAndExportJobs() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for system-admin/audit coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();

            APIResponse systemAdminsResponse = session.get("/api/v1/system-admins");
            ApiDiagnostics.writeSnippet("system-admins-authenticated", "GET /api/v1/system-admins with auth", systemAdminsResponse);
            assumeTrue(systemAdminsResponse.status() == 200,
                    "Authenticated user is not a system admin; skipping system-admin success coverage");
            JsonNode systemAdmins = session.requireJson(systemAdminsResponse, 200);
            assertTrue(systemAdmins.isArray(), systemAdmins.toString());

            APIResponse auditLogResponse = session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/audit-log?limit=5");
            ApiDiagnostics.writeSnippet("audit-log-authenticated", "GET workspace audit log with auth", auditLogResponse);
            JsonNode auditLog = session.requireJson(auditLogResponse, 200);
            assertTrue(auditLog.path("items").isArray(), auditLog.toString());

            APIResponse retentionPolicyResponse = session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/audit-retention-policy");
            ApiDiagnostics.writeSnippet("audit-retention-policy-authenticated", "GET workspace audit retention policy with auth", retentionPolicyResponse);
            JsonNode retentionPolicy = session.requireJson(retentionPolicyResponse, 200);
            assertEquals(workspace.workspaceId(), retentionPolicy.path("workspaceId").asText(), retentionPolicy.toString());

            try {
                JsonNode updatedRetentionPolicy = session.requireJson(session.put(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/audit-retention-policy",
                        JsonSupport.object("retentionEnabled", false)
                ), 200);
                assertEquals(false, updatedRetentionPolicy.path("retentionEnabled").asBoolean(), updatedRetentionPolicy.toString());

                JsonNode retentionExport = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/audit-retention-policy/export?limit=5",
                        Map.of()
                ), 200);
                String exportJobId = retentionExport.path("exportJobId").asText();
                assertEquals(workspace.workspaceId(), retentionExport.path("workspaceId").asText(), retentionExport.toString());
                assertTrue(retentionExport.path("entries").isArray(), retentionExport.toString());
                assertTrue(retentionExport.path("storageKey").isMissingNode()
                        || retentionExport.path("storageKey").isNull()
                        || retentionExport.path("storageKey").asText("").isBlank(), retentionExport.toString());

                JsonNode exportJob = session.requireJson(session.get(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/export-jobs/" + exportJobId
                ), 200);
                assertEquals("audit_retention", exportJob.path("exportType").asText(), exportJob.toString());
                APIResponse exportDownload = session.get("/api/v1/workspaces/" + workspace.workspaceId()
                        + "/export-jobs/" + exportJobId + "/download");
                ApiDiagnostics.writeSnippet("audit-retention-export-download", "GET audit retention export artifact", exportDownload);
                assertEquals(200, exportDownload.status(), exportDownload.text());
                assertTrue(exportDownload.headers().getOrDefault("content-disposition", "").contains("audit-retention"), exportDownload.headers().toString());
                assertTrue(exportDownload.text().contains("\"entries\""), exportDownload.text());
                assertTrue(!exportDownload.text().toLowerCase().contains("password"), exportDownload.text());

                JsonNode retentionPrune = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/audit-retention-policy/prune",
                        Map.of()
                ), 200);
                assertEquals(0, retentionPrune.path("entriesPruned").asLong(), retentionPrune.toString());

                JsonNode replay = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/domain-events/replay",
                        JsonSupport.object("consumerKeys", java.util.List.of("activity-projection", "audit-projection"), "includePublished", false)
                ), 200);
                assertEquals(workspace.workspaceId(), replay.path("workspaceId").asText(), replay.toString());
                assertTrue(replay.path("consumerKeys").isArray(), replay.toString());

                JsonNode workspaceRoles = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/roles"), 200);
                String memberRoleId = findByField(workspaceRoles, "key", "member").path("id").asText();
                assertTrue(!memberRoleId.isBlank(), workspaceRoles.toString());
                JsonNode user = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/users", JsonSupport.object(
                        "email", "system-admin-target-" + suffix + "@example.test",
                        "username", "system-admin-target-" + suffix,
                        "displayName", "System Admin Target " + suffix,
                        "password", "correct-horse-battery-staple",
                        "roleId", memberRoleId,
                        "emailVerified", true
                )), 201);
                String userId = user.path("id").asText();
                cleanup.delete(session, "/api/v1/workspaces/" + workspace.workspaceId() + "/users/" + userId);
                JsonNode granted = session.requireJson(session.post("/api/v1/system-admins", JsonSupport.object("userId", userId)), 201);
                assertEquals(userId, granted.path("userId").asText(), granted.toString());
                assertTrue(granted.path("active").asBoolean(), granted.toString());
                JsonNode revoked = session.requireJson(session.delete("/api/v1/system-admins/" + userId), 200);
                assertEquals(userId, revoked.path("userId").asText(), revoked.toString());
                assertTrue(!revoked.path("active").asBoolean(), revoked.toString());
            } finally {
                restoreRetentionPolicy(session, workspace.workspaceId(), retentionPolicy);
            }

            APIResponse exportJobsResponse = session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/export-jobs?limit=5");
            ApiDiagnostics.writeSnippet("export-jobs-authenticated", "GET workspace export jobs with auth", exportJobsResponse);
            JsonNode exportJobs = session.requireJson(exportJobsResponse, 200);
            assertTrue(exportJobs.path("items").isArray(), exportJobs.toString());
        }
    }

    private void assertUnauthorized(APIResponse response, String snippetName) {
        ApiDiagnostics.writeSnippet(snippetName, snippetName, response);
        assertTrue(response.status() == 401 || response.status() == 403, response.text());
    }

    private void restoreRetentionPolicy(AuthSession session, String workspaceId, JsonNode retentionPolicy) {
        session.requireJson(session.put(
                "/api/v1/workspaces/" + workspaceId + "/audit-retention-policy",
                JsonSupport.object(
                        "retentionEnabled", retentionPolicy.path("retentionEnabled").asBoolean(false),
                        "retentionDays", retentionPolicy.path("retentionDays").isMissingNode() || retentionPolicy.path("retentionDays").isNull()
                                ? null
                                : retentionPolicy.path("retentionDays").asInt()
                )
        ), 200);
    }

    private JsonNode findByField(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        return JsonSupport.read("{}");
    }
}
