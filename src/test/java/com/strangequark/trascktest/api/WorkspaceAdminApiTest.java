package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

            APIResponse listInvitations = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/invitations");
            ApiDiagnostics.writeSnippet("list-invitations-unauthenticated", "GET invitations without auth", listInvitations);
            assertTrue(listInvitations.status() == 401 || listInvitations.status() == 403, listInvitations.text());

            APIResponse deleteInvitation = request.delete("/api/v1/workspaces/" + workspace.workspaceId() + "/invitations/00000000-0000-0000-0000-000000000001");
            ApiDiagnostics.writeSnippet("delete-invitation-unauthenticated", "DELETE invitation without auth", deleteInvitation);
            assertTrue(deleteInvitation.status() == 401 || deleteInvitation.status() == 403, deleteInvitation.text());

            APIResponse users = request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/users");
            ApiDiagnostics.writeSnippet("workspace-users-unauthenticated", "POST workspace user without auth", users);
            assertTrue(users.status() == 401 || users.status() == 403, users.text());

            APIResponse listUsers = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/users");
            ApiDiagnostics.writeSnippet("list-workspace-users-unauthenticated", "GET workspace users without auth", listUsers);
            assertTrue(listUsers.status() == 401 || listUsers.status() == 403, listUsers.text());

            APIResponse workspaceRoles = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/roles");
            ApiDiagnostics.writeSnippet("workspace-roles-unauthenticated", "GET workspace roles without auth", workspaceRoles);
            assertTrue(workspaceRoles.status() == 401 || workspaceRoles.status() == 403, workspaceRoles.text());

            APIResponse projectRoles = request.get("/api/v1/projects/" + workspace.projectId() + "/roles");
            ApiDiagnostics.writeSnippet("project-roles-unauthenticated", "GET project roles without auth", projectRoles);
            assertTrue(projectRoles.status() == 401 || projectRoles.status() == 403, projectRoles.text());

            APIResponse deleteUser = request.delete("/api/v1/workspaces/" + workspace.workspaceId() + "/users/00000000-0000-0000-0000-000000000001");
            ApiDiagnostics.writeSnippet("delete-workspace-user-unauthenticated", "DELETE workspace user without auth", deleteUser);
            assertTrue(deleteUser.status() == 401 || deleteUser.status() == 403, deleteUser.text());
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

            JsonNode workspaceRoles = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/roles"), 200);
            String memberRoleId = findByField(workspaceRoles, "key", "member").path("id").asText();
            assertFalse(memberRoleId.isBlank(), workspaceRoles.toString());
            JsonNode projectRoles = session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/roles"), 200);
            assertEquals("project", findByField(projectRoles, "key", "project_admin").path("scope").asText(), projectRoles.toString());

            JsonNode workspacePermissions = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/roles/permissions"), 200);
            assertTrue(containsPermission(workspacePermissions, "work_item.read"), workspacePermissions.toString());
            JsonNode managedWorkspaceRole = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/roles", JsonSupport.object(
                    "key", "playwright_workspace_role_" + suffix,
                    "name", "Playwright Workspace Role " + suffix,
                    "description", "Temporary workspace role for Java Playwright coverage",
                    "permissionKeys", List.of("workspace.read", "work_item.read")
            )), 201);
            String managedWorkspaceRoleId = managedWorkspaceRole.path("id").asText();
            cleanup.delete(session, "/api/v1/workspaces/" + workspace.workspaceId() + "/roles/" + managedWorkspaceRoleId);
            assertEquals("workspace", managedWorkspaceRole.path("scope").asText(), managedWorkspaceRole.toString());
            JsonNode workspaceRoleDetail = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/roles/" + managedWorkspaceRoleId), 200);
            assertTrue(permissionKeys(workspaceRoleDetail).contains("work_item.read"), workspaceRoleDetail.toString());
            JsonNode renamedWorkspaceRole = session.requireJson(session.patch("/api/v1/workspaces/" + workspace.workspaceId() + "/roles/" + managedWorkspaceRoleId, JsonSupport.object(
                    "name", "Renamed Workspace Role " + suffix,
                    "description", "Renamed through Java Playwright coverage"
            )), 200);
            assertEquals("Renamed Workspace Role " + suffix, renamedWorkspaceRole.path("name").asText(), renamedWorkspaceRole.toString());
            List<String> reducedWorkspacePermissions = withoutPermission(workspaceRoleDetail, "work_item.read");
            JsonNode workspaceRolePreview = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/roles/" + managedWorkspaceRoleId + "/permission-preview", JsonSupport.object(
                    "permissionKeys", reducedWorkspacePermissions
            )), 200);
            assertTrue(workspaceRolePreview.path("removedPermissionKeys").toString().contains("work_item.read"), workspaceRolePreview.toString());
            APIResponse unconfirmedWorkspaceUpdate = session.put("/api/v1/workspaces/" + workspace.workspaceId() + "/roles/" + managedWorkspaceRoleId + "/permissions", JsonSupport.object(
                    "permissionKeys", reducedWorkspacePermissions,
                    "confirmed", false,
                    "previewToken", workspaceRolePreview.path("previewToken").asText()
            ));
            ApiDiagnostics.writeSnippet("workspace-role-permission-unconfirmed", "PUT workspace role permissions without confirmation", unconfirmedWorkspaceUpdate);
            assertEquals(409, unconfirmedWorkspaceUpdate.status(), unconfirmedWorkspaceUpdate.text());
            JsonNode updatedWorkspaceRole = session.requireJson(session.put("/api/v1/workspaces/" + workspace.workspaceId() + "/roles/" + managedWorkspaceRoleId + "/permissions", JsonSupport.object(
                    "permissionKeys", reducedWorkspacePermissions,
                    "confirmed", true,
                    "previewToken", workspaceRolePreview.path("previewToken").asText()
            )), 200);
            assertFalse(permissionKeys(updatedWorkspaceRole).contains("work_item.read"), updatedWorkspaceRole.toString());
            JsonNode workspaceRoleVersions = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/roles/" + managedWorkspaceRoleId + "/versions"), 200);
            JsonNode workspaceCreatedVersion = findByField(workspaceRoleVersions, "changeType", "created");
            JsonNode rolledBackWorkspaceRole = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/roles/" + managedWorkspaceRoleId + "/versions/" + workspaceCreatedVersion.path("id").asText() + "/rollback", Map.of()), 200);
            assertTrue(permissionKeys(rolledBackWorkspaceRole).contains("work_item.read"), rolledBackWorkspaceRole.toString());
            assertEquals(204, session.delete("/api/v1/workspaces/" + workspace.workspaceId() + "/roles/" + managedWorkspaceRoleId).status());

            JsonNode projectPermissions = session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/roles/permissions"), 200);
            assertTrue(containsPermission(projectPermissions, "project.read"), projectPermissions.toString());
            JsonNode managedProjectRole = session.requireJson(session.post("/api/v1/projects/" + workspace.projectId() + "/roles", JsonSupport.object(
                    "key", "playwright_project_role_" + suffix,
                    "name", "Playwright Project Role " + suffix,
                    "description", "Temporary project role for Java Playwright coverage",
                    "permissionKeys", List.of("project.read", "work_item.read")
            )), 201);
            String managedProjectRoleId = managedProjectRole.path("id").asText();
            cleanup.delete(session, "/api/v1/projects/" + workspace.projectId() + "/roles/" + managedProjectRoleId);
            assertEquals("project", managedProjectRole.path("scope").asText(), managedProjectRole.toString());
            JsonNode projectRoleDetail = session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/roles/" + managedProjectRoleId), 200);
            assertTrue(permissionKeys(projectRoleDetail).contains("work_item.read"), projectRoleDetail.toString());
            JsonNode renamedProjectRole = session.requireJson(session.patch("/api/v1/projects/" + workspace.projectId() + "/roles/" + managedProjectRoleId, JsonSupport.object(
                    "name", "Renamed Project Role " + suffix,
                    "description", "Renamed through Java Playwright coverage"
            )), 200);
            assertEquals("Renamed Project Role " + suffix, renamedProjectRole.path("name").asText(), renamedProjectRole.toString());
            List<String> reducedProjectPermissions = withoutPermission(projectRoleDetail, "work_item.read");
            JsonNode projectRolePreview = session.requireJson(session.post("/api/v1/projects/" + workspace.projectId() + "/roles/" + managedProjectRoleId + "/permission-preview", JsonSupport.object(
                    "permissionKeys", reducedProjectPermissions
            )), 200);
            JsonNode updatedProjectRole = session.requireJson(session.put("/api/v1/projects/" + workspace.projectId() + "/roles/" + managedProjectRoleId + "/permissions", JsonSupport.object(
                    "permissionKeys", reducedProjectPermissions,
                    "confirmed", true,
                    "previewToken", projectRolePreview.path("previewToken").asText()
            )), 200);
            assertFalse(permissionKeys(updatedProjectRole).contains("work_item.read"), updatedProjectRole.toString());
            JsonNode projectRoleVersions = session.requireJson(session.get("/api/v1/projects/" + workspace.projectId() + "/roles/" + managedProjectRoleId + "/versions"), 200);
            JsonNode projectCreatedVersion = findByField(projectRoleVersions, "changeType", "created");
            JsonNode rolledBackProjectRole = session.requireJson(session.post("/api/v1/projects/" + workspace.projectId() + "/roles/" + managedProjectRoleId + "/versions/" + projectCreatedVersion.path("id").asText() + "/rollback", Map.of()), 200);
            assertTrue(permissionKeys(rolledBackProjectRole).contains("work_item.read"), rolledBackProjectRole.toString());
            assertEquals(204, session.delete("/api/v1/projects/" + workspace.projectId() + "/roles/" + managedProjectRoleId).status());

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
                    "roleId", memberRoleId,
                    "expiresAt", OffsetDateTime.now().plusDays(7).toString()
            )), 201);
            String invitationId = invitation.path("id").asText();
            cleanup.delete(session, "/api/v1/workspaces/" + workspace.workspaceId() + "/invitations/" + invitationId);
            assertFalse(invitation.path("token").asText().isBlank(), invitation.toString());
            JsonNode pendingInvitations = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/invitations"), 200);
            JsonNode listedInvitation = findByField(pendingInvitations, "id", invitationId);
            assertEquals("playwright-invite-" + suffix + "@example.test", listedInvitation.path("email").asText(), listedInvitation.toString());
            assertEquals("pending", listedInvitation.path("status").asText(), listedInvitation.toString());
            assertFalse(listedInvitation.has("token"), listedInvitation.toString());
            assertFalse(listedInvitation.has("tokenHash"), listedInvitation.toString());
            assertEquals(204, session.delete("/api/v1/workspaces/" + workspace.workspaceId() + "/invitations/" + invitationId).status());

            APIRequestContext rawRequest = ApiRequestFactory.backend(playwright, config);
            try {
                APIResponse revokedInvitationRegister = rawRequest.post("/api/v1/auth/register", RequestOptions.create().setData(JsonSupport.object(
                        "email", "playwright-invite-" + suffix + "@example.test",
                        "username", "playwright-revoked-" + suffix,
                        "displayName", "Playwright Revoked " + suffix,
                        "password", "correct-horse-battery-staple",
                        "invitationToken", invitation.path("token").asText()
                )));
                ApiDiagnostics.writeSnippet("revoked-invitation-register", "POST register with revoked invitation", revokedInvitationRegister);
                assertEquals(403, revokedInvitationRegister.status(), revokedInvitationRegister.text());
            } finally {
                rawRequest.dispose();
            }

            JsonNode user = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/users", JsonSupport.object(
                    "email", "playwright-user-" + suffix + "@example.test",
                    "username", "playwright-user-" + suffix,
                    "displayName", "Playwright User " + suffix,
                    "password", "correct-horse-battery-staple",
                    "roleId", memberRoleId,
                    "emailVerified", true
            )), 201);
            String userId = user.path("id").asText();
            cleanup.delete(session, "/api/v1/workspaces/" + workspace.workspaceId() + "/users/" + userId);
            assertTrue(user.path("emailVerified").asBoolean(), user.toString());
            JsonNode activeUsers = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/users"), 200);
            JsonNode listedUser = findByField(activeUsers, "userId", userId);
            assertEquals("playwright-user-" + suffix + "@example.test", listedUser.path("email").asText(), listedUser.toString());
            assertEquals("active", listedUser.path("status").asText(), listedUser.toString());
            assertFalse(listedUser.has("passwordHash"), listedUser.toString());
            assertEquals(204, session.delete("/api/v1/workspaces/" + workspace.workspaceId() + "/users/" + userId).status());

            APIRequestContext removedUserRequest = ApiRequestFactory.backend(playwright, config);
            try {
                APIResponse removedUserLogin = removedUserRequest.post("/api/v1/auth/login", RequestOptions.create().setData(JsonSupport.object(
                        "identifier", "playwright-user-" + suffix + "@example.test",
                        "password", "correct-horse-battery-staple"
                )));
                ApiDiagnostics.writeSnippet("removed-workspace-user-login", "POST login after workspace user removal", removedUserLogin);
                assertEquals(401, removedUserLogin.status(), removedUserLogin.text());
            } finally {
                removedUserRequest.dispose();
            }
        }
    }

    private static JsonNode findByField(JsonNode items, String field, String value) {
        for (JsonNode item : items) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("Expected " + field + "=" + value + " in " + items);
    }

    private static boolean containsPermission(JsonNode permissions, String key) {
        for (JsonNode permission : permissions) {
            if (key.equals(permission.path("key").asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> permissionKeys(JsonNode role) {
        List<String> keys = new ArrayList<>();
        for (JsonNode key : role.path("permissionKeys")) {
            keys.add(key.asText());
        }
        return keys;
    }

    private static List<String> withoutPermission(JsonNode role, String removedKey) {
        return permissionKeys(role).stream()
                .filter(key -> !removedKey.equals(key))
                .toList();
    }
}
