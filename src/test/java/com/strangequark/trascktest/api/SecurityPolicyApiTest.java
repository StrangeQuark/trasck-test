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
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.TestWorkspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("security")
class SecurityPolicyApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void workspaceAndProjectPoliciesRequireAuthentication() {
        TestWorkspace workspace = TestWorkspace.require(config);
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);
            APIResponse workspacePolicy = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy");
            ApiDiagnostics.writeSnippet("workspace-policy-unauthenticated", "GET workspace security policy without auth", workspacePolicy);
            assertTrue(workspacePolicy.status() == 401 || workspacePolicy.status() == 403, workspacePolicy.text());

            APIResponse projectPolicy = request.get("/api/v1/projects/" + workspace.projectId() + "/security-policy");
            ApiDiagnostics.writeSnippet("project-policy-unauthenticated", "GET project security policy without auth", projectPolicy);
            assertTrue(projectPolicy.status() == 401 || projectPolicy.status() == 403, projectPolicy.text());
            request.dispose();
        }
    }

    @Test
    void authenticatedWorkspaceAndProjectPoliciesExposeEffectiveLimitsAndRejectInvalidUpdates() {
        assumeTrue(config.hasLoginCredentials(), "Set TRASCK_E2E_LOGIN_IDENTIFIER and TRASCK_E2E_LOGIN_PASSWORD for authenticated API coverage");
        TestWorkspace workspace = TestWorkspace.require(config);
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config)) {
            APIResponse workspacePolicyResponse = session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy");
            ApiDiagnostics.writeSnippet("workspace-policy-authenticated", "GET workspace security policy with auth", workspacePolicyResponse);
            JsonNode workspacePolicy = session.requireJson(workspacePolicyResponse, 200);
            assertEquals(workspace.workspaceId(), workspacePolicy.path("workspaceId").asText());
            assertTrue(workspacePolicy.path("attachmentMaxUploadBytes").asLong() > 0, workspacePolicy.toString());
            assertFalse(workspacePolicy.path("attachmentAllowedContentTypes").asText().isBlank(), workspacePolicy.toString());

            APIResponse projectPolicyResponse = session.get("/api/v1/projects/" + workspace.projectId() + "/security-policy");
            ApiDiagnostics.writeSnippet("project-policy-authenticated", "GET project security policy with auth", projectPolicyResponse);
            JsonNode projectPolicy = session.requireJson(projectPolicyResponse, 200);
            assertEquals(workspace.projectId(), projectPolicy.path("projectId").asText());
            assertEquals(workspace.workspaceId(), projectPolicy.path("workspaceId").asText());
            assertTrue(projectPolicy.path("attachmentMaxDownloadBytes").asLong() > 0, projectPolicy.toString());

            APIResponse invalidUpdate = session.patch(
                    "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                    JsonSupport.object("attachmentMaxUploadBytes", 0)
            );
            ApiDiagnostics.writeSnippet("project-policy-invalid-update", "PATCH project security policy with invalid size", invalidUpdate);
            assertEquals(400, invalidUpdate.status(), invalidUpdate.text());
        }
    }
}
