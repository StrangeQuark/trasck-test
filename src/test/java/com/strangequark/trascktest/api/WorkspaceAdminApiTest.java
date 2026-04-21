package com.strangequark.trascktest.api;

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
@Tag("workspace-admin")
class WorkspaceAdminApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void workspaceAdminSurfacesRequireAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);

            APIResponse repositories = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/repository-connections");
            ApiDiagnostics.writeSnippet("repository-connections-unauthenticated", "GET repository connections without auth", repositories);
            assertTrue(repositories.status() == 401 || repositories.status() == 403, repositories.text());

            APIResponse invitations = request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/invitations");
            ApiDiagnostics.writeSnippet("invitations-unauthenticated", "POST invitation without auth", invitations);
            assertTrue(invitations.status() == 401 || invitations.status() == 403, invitations.text());

            APIResponse users = request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/users");
            ApiDiagnostics.writeSnippet("workspace-users-unauthenticated", "POST workspace user without auth", users);
            assertTrue(users.status() == 401 || users.status() == 403, users.text());
            request.dispose();
        }
    }

    @Test
    void authenticatedAdminsCanManageRepositoryConnectionsInvitationsAndWorkspaceUsers() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for workspace admin API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();

            JsonNode repository = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/repository-connections", JsonSupport.object(
                    "projectId", workspace.projectId(),
                    "provider", "github",
                    "name", "Playwright Repository " + suffix,
                    "repositoryUrl", "https://github.com/strangequark/trasck-" + suffix + ".git",
                    "defaultBranch", "main",
                    "providerMetadata", Map.of("owner", "strangequark", "name", "trasck-" + suffix),
                    "config", Map.of("checkout", "shallow"),
                    "active", true
            )), 201);
            String repositoryId = repository.path("id").asText();
            cleanup.delete(session, "/api/v1/workspaces/" + workspace.workspaceId() + "/repository-connections/" + repositoryId);
            assertFalse(repository.path("config").path("providerMetadata").path("owner").asText().isBlank(), repository.toString());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/repository-connections"), 200).isArray());
            assertTrue(session.delete("/api/v1/workspaces/" + workspace.workspaceId() + "/repository-connections/" + repositoryId).status() == 204);

            JsonNode invitation = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/invitations", JsonSupport.object(
                    "email", "playwright-invite-" + suffix + "@example.test",
                    "expiresAt", "2026-04-28T00:00:00Z"
            )), 201);
            assertFalse(invitation.path("token").asText().isBlank(), invitation.toString());

            JsonNode user = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/users", JsonSupport.object(
                    "email", "playwright-user-" + suffix + "@example.test",
                    "username", "playwright-user-" + suffix,
                    "displayName", "Playwright User " + suffix,
                    "password", "correct-horse-battery-staple",
                    "emailVerified", true
            )), 201);
            assertTrue(user.path("emailVerified").asBoolean(), user.toString());
        }
    }
}
