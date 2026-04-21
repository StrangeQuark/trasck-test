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
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
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
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for authenticated API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config)) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
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

            boolean originalAnonymousRead = workspacePolicy.path("anonymousReadEnabled").asBoolean(false);
            String originalVisibility = projectPolicy.path("visibility").asText("private");
            APIRequestContext anonymous = ApiRequestFactory.backend(playwright, config);
            try {
                JsonNode privateProjectPolicy = session.requireJson(session.patch(
                        "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                        JsonSupport.object("visibility", "private")
                ), 200);
                assertEquals("private", privateProjectPolicy.path("visibility").asText(), privateProjectPolicy.toString());
                JsonNode closedWorkspacePolicy = session.requireJson(session.patch(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy",
                        JsonSupport.object("anonymousReadEnabled", false)
                ), 200);
                assertFalse(closedWorkspacePolicy.path("anonymousReadEnabled").asBoolean(), closedWorkspacePolicy.toString());

                APIResponse closedPublicProject = anonymous.get("/api/v1/public/projects/" + workspace.projectId());
                ApiDiagnostics.writeSnippet("public-project-closed", "GET public project while workspace/project public read is disabled", closedPublicProject);
                assertEquals(404, closedPublicProject.status(), closedPublicProject.text());

                JsonNode openWorkspacePolicy = session.requireJson(session.patch(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy",
                        JsonSupport.object("anonymousReadEnabled", true)
                ), 200);
                assertTrue(openWorkspacePolicy.path("anonymousReadEnabled").asBoolean(), openWorkspacePolicy.toString());
                JsonNode publicProjectPolicy = session.requireJson(session.patch(
                        "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                        JsonSupport.object("visibility", "public")
                ), 200);
                assertEquals("public", publicProjectPolicy.path("visibility").asText(), publicProjectPolicy.toString());
                assertTrue(publicProjectPolicy.path("publicReadEnabled").asBoolean(), publicProjectPolicy.toString());

                APIResponse publicProjectResponse = anonymous.get("/api/v1/public/projects/" + workspace.projectId());
                ApiDiagnostics.writeSnippet("public-project-open", "GET public project while workspace/project public read is enabled", publicProjectResponse);
                assertEquals(200, publicProjectResponse.status(), publicProjectResponse.text());
                JsonNode publicProject = JsonSupport.read(publicProjectResponse.text());
                assertEquals(workspace.projectId(), publicProject.path("id").asText(), publicProject.toString());
                assertEquals("public", publicProject.path("visibility").asText(), publicProject.toString());
            } finally {
                session.patch(
                        "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                        JsonSupport.object("visibility", originalVisibility)
                );
                session.patch(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy",
                        JsonSupport.object("anonymousReadEnabled", originalAnonymousRead)
                );
                anonymous.dispose();
            }
        }
    }
}
