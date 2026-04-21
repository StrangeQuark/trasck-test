package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.TestWorkspace;
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

            APIResponse systemAdmins = request.get("/api/v1/system-admins");
            ApiDiagnostics.writeSnippet("system-admins-unauthenticated", "GET /api/v1/system-admins without auth", systemAdmins);
            assertTrue(systemAdmins.status() == 401 || systemAdmins.status() == 403, systemAdmins.text());

            APIResponse auditLog = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/audit-log?limit=5");
            ApiDiagnostics.writeSnippet("audit-log-unauthenticated", "GET workspace audit log without auth", auditLog);
            assertTrue(auditLog.status() == 401 || auditLog.status() == 403, auditLog.text());

            APIResponse exportJobs = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/export-jobs?limit=5");
            ApiDiagnostics.writeSnippet("export-jobs-unauthenticated", "GET workspace export jobs without auth", exportJobs);
            assertTrue(exportJobs.status() == 401 || exportJobs.status() == 403, exportJobs.text());
            request.dispose();
        }
    }

    @Test
    void authenticatedAdminCanListSystemAdminsAuditLogAndExportJobs() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for system-admin/audit coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config)) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);

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

            APIResponse exportJobsResponse = session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/export-jobs?limit=5");
            ApiDiagnostics.writeSnippet("export-jobs-authenticated", "GET workspace export jobs with auth", exportJobsResponse);
            JsonNode exportJobs = session.requireJson(exportJobsResponse, 200);
            assertTrue(exportJobs.path("items").isArray(), exportJobs.toString());
        }
    }
}
