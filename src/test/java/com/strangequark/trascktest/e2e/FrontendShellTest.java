package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.BrowserSession;
import com.strangequark.trascktest.support.RuntimeChecks;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("smoke")
class FrontendShellTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void frontendShellLoadsCoreNavigation() {
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession session = BrowserSession.start(browser, config, "frontend-shell")) {
            Page page = session.page();
            page.addInitScript("localStorage.setItem('trasck.apiBaseUrl', '" + config.frontendBaseUrl() + "');");
            mockCurrentUser(page);
            page.navigate("/");

            assertThat(page.locator(".app-kicker")).hasText("Trasck");
            Locator primaryNavigation = page.locator("nav[aria-label='Primary']");
            assertThat(navigationLink(primaryNavigation, "Setup")).isVisible();
            assertThat(navigationLink(primaryNavigation, "Auth")).isVisible();
            assertThat(navigationLink(primaryNavigation, "Work")).isVisible();
            assertThat(navigationLink(primaryNavigation, "Programs")).isVisible();
            assertThat(navigationLink(primaryNavigation, "System")).isVisible();
            assertThat(navigationLink(primaryNavigation, "Workspace")).isVisible();
            assertThat(navigationLink(primaryNavigation, "Project")).isVisible();
            session.screenshot();
            session.assertNoConsoleErrors();
        }
    }

    @Test
    void workspaceSettingsManagesMembersAndInvitationsThroughBrowserUi() {
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        String workspaceId = "00000000-0000-0000-0000-000000000101";
        AtomicBoolean userCreated = new AtomicBoolean(false);
        AtomicBoolean userRemoved = new AtomicBoolean(false);
        AtomicBoolean invitationCreated = new AtomicBoolean(false);
        AtomicBoolean invitationRevoked = new AtomicBoolean(false);

        try (Playwright playwright = Playwright.create();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession session = BrowserSession.start(browser, config, "workspace-settings-ui")) {
            Page page = session.page();
            page.addInitScript("localStorage.setItem('trasck.workspaceId', '" + workspaceId + "');"
                    + "localStorage.setItem('trasck.apiBaseUrl', '" + config.frontendBaseUrl() + "');");
            mockCurrentUser(page);
            mockCsrf(page);
            mockWorkspaceUsers(page, workspaceId, userCreated, userRemoved);
            mockWorkspaceInvitations(page, workspaceId, invitationCreated, invitationRevoked);
            mockWorkspaceRoles(page, workspaceId);
            mockWorkspaceSecurityPolicy(page, workspaceId);

            page.navigate("/workspace-settings");

            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Workspace Security Policy"))).isVisible();
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Workspace Members"))).isVisible();
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Workspace Invitations"))).isVisible();

            page.getByLabel("User email").fill("browser-user@example.test");
            page.getByLabel("Username").fill("browser-user");
            page.getByLabel("Display name").fill("Browser User");
            page.getByLabel("Password").fill("correct-horse-battery-staple");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create User")).click();
            assertThat(page.getByText("Browser User").first()).isVisible();

            page.onceDialog(dialog -> dialog.accept());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Remove")).click();
            assertThat(page.getByText("No workspace members loaded")).isVisible();

            page.getByLabel("Invitation email").fill("browser-invite@example.test");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Invite")).click();
            assertThat(page.getByText("browser-invite@example.test").first()).isVisible();

            page.onceDialog(dialog -> dialog.accept());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Revoke")).click();
            assertThat(page.getByText("No invitations loaded")).isVisible();

            session.screenshot();
            session.assertNoConsoleErrors();
        }
    }

    @Test
    void projectPublicReadSettingsExposePreviewRoute() {
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        String workspaceId = "00000000-0000-0000-0000-000000000101";
        String projectId = "00000000-0000-0000-0000-000000000501";
        AtomicBoolean publicEnabled = new AtomicBoolean(false);

        try (Playwright playwright = Playwright.create();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession session = BrowserSession.start(browser, config, "project-public-read-ui")) {
            Page page = session.page();
            page.addInitScript("localStorage.setItem('trasck.workspaceId', '" + workspaceId + "');"
                    + "localStorage.setItem('trasck.projectId', '" + projectId + "');"
                    + "localStorage.setItem('trasck.apiBaseUrl', '" + config.frontendBaseUrl() + "');");
            mockCurrentUser(page);
            mockCsrf(page);
            mockProjectSecurityPolicy(page, projectId, publicEnabled);
            mockProjectRoles(page, projectId);
            mockPublicProject(page, workspaceId, projectId, publicEnabled);
            mockPublicProjectWorkItems(page, projectId, publicEnabled);

            page.navigate("/project-settings");

            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Project Security Policy"))).isVisible();
            page.getByLabel("Visibility").selectOption("public");
            page.onceDialog(dialog -> dialog.accept());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Public Preview"))).isVisible();

            page.navigate("/public/projects/" + projectId);
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Public Project Preview"))).isVisible();
            assertThat(page.getByText("Browser Public Project").first()).isVisible();
            assertThat(page.getByText("Browser public story").first()).isVisible();
            page.getByText("Browser public story").click();
            assertThat(page.getByText("Browser public collaboration note").first()).isVisible();
            assertThat(page.getByText("browser-public-notes.txt").first()).isVisible();

            session.screenshot();
            session.assertNoConsoleErrors();
        }
    }

    @Test
    void programsPageManagesProgramPortfolioThroughBrowserUi() {
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        String workspaceId = "00000000-0000-0000-0000-000000000101";
        String projectId = "00000000-0000-0000-0000-000000000501";
        String programId = "00000000-0000-0000-0000-000000000801";
        AtomicBoolean programCreated = new AtomicBoolean(false);
        AtomicBoolean projectAssigned = new AtomicBoolean(false);
        AtomicBoolean projectRemoved = new AtomicBoolean(false);
        AtomicBoolean programArchived = new AtomicBoolean(false);

        try (Playwright playwright = Playwright.create();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession session = BrowserSession.start(browser, config, "programs-ui")) {
            Page page = session.page();
            page.addInitScript("localStorage.setItem('trasck.workspaceId', '" + workspaceId + "');"
                    + "localStorage.setItem('trasck.projectId', '" + projectId + "');"
                    + "localStorage.setItem('trasck.apiBaseUrl', '" + config.frontendBaseUrl() + "');");
            mockCurrentUser(page);
            mockCsrf(page);
            mockPrograms(page, workspaceId, projectId, programId, programCreated, projectAssigned, projectRemoved, programArchived);

            page.navigate("/programs");

            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Program Portfolio"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create program")).click();
            assertThat(page.locator("input[value='Browser Portfolio']").first()).isVisible();

            page.getByLabel("Project ID").fill(projectId);
            page.getByLabel("Position").fill("2");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign project")).click();
            assertThat(page.getByText(projectId).first()).isVisible();

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load summary")).click();
            assertThat(page.getByText("program").first()).isVisible();

            page.onceDialog(dialog -> dialog.accept());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Archive")).click();
            assertTrue(programArchived.get(), "Archive endpoint should be called from the browser flow");
            assertThat(page.getByLabel("Status").first()).hasValue("archived");

            session.screenshot();
            session.assertNoConsoleErrors();
        }
    }

    private static void mockCurrentUser(Page page) {
        page.route("**/api/v1/auth/me", route -> fulfillJson(route, 200, """
                {
                  "id": "00000000-0000-0000-0000-000000000001",
                  "email": "admin@example.test",
                  "username": "admin",
                  "displayName": "Admin User",
                  "accountType": "human",
                  "emailVerified": true
                }
                """));
    }

    private static void mockCsrf(Page page) {
        page.route("**/api/v1/auth/csrf", route -> fulfillJson(route, 200, """
                {
                  "headerName": "X-CSRF-TOKEN",
                  "parameterName": "_csrf",
                  "token": "browser-test-csrf"
                }
                """));
    }

    private static void mockWorkspaceRoles(Page page, String workspaceId) {
        String roleId = "00000000-0000-0000-0000-000000000401";
        page.route("**/api/v1/workspaces/" + workspaceId + "/roles", route -> fulfillJson(route, 200, """
                [{
                  "id": "%s",
                  "workspaceId": "00000000-0000-0000-0000-000000000101",
                  "key": "member",
                  "name": "Member",
                  "scope": "workspace",
                  "description": "Creates and updates project work.",
                  "systemRole": true,
                  "status": "active",
                  "permissionKeys": ["workspace.read", "project.read", "work_item.read", "work_item.update"],
                  "impactSummary": {
                    "activeMembers": 1,
                    "pendingInvitations": 0,
                    "affectedUsers": 1,
                    "affectsCurrentUser": false
                  }
                }]
                """.formatted(roleId)));
        page.route("**/api/v1/workspaces/" + workspaceId + "/roles/permissions", route -> fulfillJson(route, 200, rolePermissionCatalog()));
        page.route("**/api/v1/workspaces/" + workspaceId + "/roles/" + roleId, route -> fulfillJson(route, 200, roleJson(roleId, workspaceId, null, "workspace", "member", "Member")));
        page.route("**/api/v1/workspaces/" + workspaceId + "/roles/" + roleId + "/versions", route -> fulfillJson(route, 200, roleVersionsJson(roleId)));
    }

    private static void mockProjectRoles(Page page, String projectId) {
        String roleId = "00000000-0000-0000-0000-000000000402";
        page.route("**/api/v1/projects/" + projectId + "/roles", route -> fulfillJson(route, 200, """
                [{
                  "id": "%s",
                  "workspaceId": "00000000-0000-0000-0000-000000000101",
                  "projectId": "%s",
                  "key": "project_admin",
                  "name": "Project Admin",
                  "scope": "project",
                  "description": "Administers the project.",
                  "systemRole": true,
                  "status": "active",
                  "permissionKeys": ["project.admin", "project.read", "work_item.read", "work_item.update"],
                  "impactSummary": {
                    "activeMembers": 1,
                    "pendingInvitations": 0,
                    "affectedUsers": 1,
                    "affectsCurrentUser": false
                  }
                }]
                """.formatted(roleId, projectId)));
        page.route("**/api/v1/projects/" + projectId + "/roles/permissions", route -> fulfillJson(route, 200, rolePermissionCatalog()));
        page.route("**/api/v1/projects/" + projectId + "/roles/" + roleId, route -> fulfillJson(route, 200, roleJson(roleId, "00000000-0000-0000-0000-000000000101", projectId, "project", "project_admin", "Project Admin")));
        page.route("**/api/v1/projects/" + projectId + "/roles/" + roleId + "/versions", route -> fulfillJson(route, 200, roleVersionsJson(roleId)));
    }

    private static void mockWorkspaceSecurityPolicy(Page page, String workspaceId) {
        page.route("**/api/v1/workspaces/" + workspaceId + "/security-policy", route -> {
            if ("GET".equals(route.request().method()) || "PATCH".equals(route.request().method())) {
                fulfillJson(route, 200, """
                        {
                          "workspaceId": "00000000-0000-0000-0000-000000000101",
                          "anonymousReadEnabled": false,
                          "attachmentMaxUploadBytes": 10485760,
                          "attachmentMaxDownloadBytes": 52428800,
                          "attachmentAllowedContentTypes": "text/plain,application/json",
                          "exportMaxArtifactBytes": 52428800,
                          "exportAllowedContentTypes": "application/json,text/csv",
                          "importMaxParseBytes": 5242880,
                          "importAllowedContentTypes": "text/csv,application/json",
                          "customPolicy": false
                        }
                        """);
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
    }

    private static void mockProjectSecurityPolicy(Page page, String projectId, AtomicBoolean publicEnabled) {
        page.route("**/api/v1/projects/" + projectId + "/security-policy", route -> {
            if ("PATCH".equals(route.request().method())) {
                publicEnabled.set(true);
            }
            if ("GET".equals(route.request().method()) || "PATCH".equals(route.request().method())) {
                fulfillJson(route, 200, publicEnabled.get() ? publicProjectPolicy(projectId) : privateProjectPolicy(projectId));
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
    }

    private static void mockPublicProject(Page page, String workspaceId, String projectId, AtomicBoolean publicEnabled) {
        page.route("**/api/v1/public/projects/" + projectId, route -> {
            if (publicEnabled.get()) {
                fulfillJson(route, 200, """
                        {
                          "id": "%s",
                          "workspaceId": "%s",
                          "name": "Browser Public Project",
                          "key": "BPP",
                          "description": "Browser public project preview",
                          "visibility": "public"
                        }
                        """.formatted(projectId, workspaceId));
                return;
            }
            fulfillJson(route, 404, """
                    {
                      "message": "Public project not found"
                    }
                    """);
        });
    }

    private static void mockPublicProjectWorkItems(Page page, String projectId, AtomicBoolean publicEnabled) {
        String workItemId = "00000000-0000-0000-0000-000000000601";
        page.route(Pattern.compile(".*/api/v1/public/projects/" + projectId + "/work-items(\\?.*)?$"), route -> {
            if (publicEnabled.get()) {
                fulfillJson(route, 200, """
                        {
                          "items": [{
                            "id": "%s",
                            "projectId": "%s",
                            "key": "BPP-1",
                            "title": "Browser public story",
                            "visibility": "inherited"
                          }],
                          "nextCursor": null,
                          "hasMore": false,
                          "limit": 25
                        }
                        """.formatted(workItemId, projectId));
                return;
            }
            fulfillJson(route, 404, """
                    {
                      "message": "Public project not found"
                    }
                    """);
        });
        page.route("**/api/v1/public/projects/" + projectId + "/work-items/" + workItemId + "/comments", route -> {
            if (publicEnabled.get()) {
                fulfillJson(route, 200, """
                        [{
                          "id": "00000000-0000-0000-0000-000000000611",
                          "workItemId": "%s",
                          "bodyMarkdown": "Browser public collaboration note",
                          "createdAt": "2026-04-21T20:00:00Z"
                        }]
                        """.formatted(workItemId));
                return;
            }
            fulfillJson(route, 404, """
                    {
                      "message": "Public project not found"
                    }
                    """);
        });
        page.route("**/api/v1/public/projects/" + projectId + "/work-items/" + workItemId + "/attachments", route -> {
            if (publicEnabled.get()) {
                fulfillJson(route, 200, """
                        [{
                          "id": "00000000-0000-0000-0000-000000000612",
                          "workItemId": "%s",
                          "filename": "browser-public-notes.txt",
                          "contentType": "text/plain",
                          "sizeBytes": 42,
                          "downloadUrl": "/api/v1/public/projects/%s/work-items/%s/attachments/00000000-0000-0000-0000-000000000612/download?token=browser-test"
                        }]
                        """.formatted(workItemId, projectId, workItemId));
                return;
            }
            fulfillJson(route, 404, """
                    {
                      "message": "Public project not found"
                    }
                    """);
        });
        page.route(Pattern.compile(".*/api/v1/public/projects/" + projectId + "/work-items/" + workItemId + "$"), route -> {
            if (publicEnabled.get()) {
                fulfillJson(route, 200, """
                        {
                          "id": "%s",
                          "projectId": "%s",
                          "key": "BPP-1",
                          "title": "Browser public story",
                          "descriptionMarkdown": "Browser public work item preview",
                          "visibility": "inherited"
                        }
                        """.formatted(workItemId, projectId));
                return;
            }
            fulfillJson(route, 404, """
                    {
                      "message": "Public project not found"
                    }
                    """);
        });
    }

    private static void mockPrograms(
            Page page,
            String workspaceId,
            String projectId,
            String programId,
            AtomicBoolean programCreated,
            AtomicBoolean projectAssigned,
            AtomicBoolean projectRemoved,
            AtomicBoolean programArchived
    ) {
        page.route("**/api/v1/workspaces/" + workspaceId + "/programs", route -> {
            String method = route.request().method();
            if ("GET".equals(method)) {
                fulfillJson(route, 200, programCreated.get() ? "[" + programJson(programId, workspaceId, programArchived.get(), projectAssigned.get() && !projectRemoved.get(), projectId) + "]" : "[]");
                return;
            }
            if ("POST".equals(method)) {
                programCreated.set(true);
                programArchived.set(false);
                fulfillJson(route, 201, programJson(programId, workspaceId, false, false, projectId));
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
        page.route("**/api/v1/programs/" + programId + "/projects/" + projectId, route -> {
            String method = route.request().method();
            if ("PUT".equals(method)) {
                projectAssigned.set(true);
                projectRemoved.set(false);
                fulfillJson(route, 200, """
                        {
                          "programId": "%s",
                          "projectId": "%s",
                          "position": 2,
                          "createdAt": "2026-04-21T17:00:00Z"
                        }
                        """.formatted(programId, projectId));
                return;
            }
            if ("DELETE".equals(method)) {
                projectRemoved.set(true);
                route.fulfill(new Route.FulfillOptions().setStatus(204));
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
        page.route("**/api/v1/programs/" + programId + "/projects", route -> {
            if ("GET".equals(route.request().method())) {
                fulfillJson(route, 200, projectAssigned.get() && !projectRemoved.get() ? """
                        [{
                          "programId": "%s",
                          "projectId": "%s",
                          "position": 2,
                          "createdAt": "2026-04-21T17:00:00Z"
                        }]
                        """.formatted(programId, projectId) : "[]");
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
        page.route("**/api/v1/reports/programs/" + programId + "/dashboard-summary**", route -> fulfillJson(route, 200, """
                {
                  "workspaceId": "%s",
                  "scope": {
                    "scopeType": "program",
                    "programId": "%s",
                    "projectIds": ["%s"]
                  },
                  "totals": {
                    "workItems": 1
                  },
                  "byProject": []
                }
                """.formatted(workspaceId, programId, projectId)));
        page.route("**/api/v1/programs/" + programId, route -> {
            String method = route.request().method();
            if ("GET".equals(method) || "PATCH".equals(method)) {
                fulfillJson(route, 200, programJson(programId, workspaceId, programArchived.get(), projectAssigned.get() && !projectRemoved.get(), projectId));
                return;
            }
            if ("DELETE".equals(method)) {
                programArchived.set(true);
                route.fulfill(new Route.FulfillOptions().setStatus(204));
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
    }

    private static String programJson(String programId, String workspaceId, boolean archived, boolean projectAssigned, String projectId) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "name": "Browser Portfolio",
                  "description": "Browser portfolio test program",
                  "status": "%s",
                  "roadmapConfig": {
                    "view": "timeline"
                  },
                  "reportConfig": {
                    "defaultWindow": "current_quarter"
                  },
                  "projects": %s,
                  "createdAt": "2026-04-21T17:00:00Z",
                  "updatedAt": "2026-04-21T17:00:00Z"
                }
                """.formatted(
                programId,
                workspaceId,
                archived ? "archived" : "active",
                projectAssigned ? """
                        [{
                          "programId": "%s",
                          "projectId": "%s",
                          "position": 2,
                          "createdAt": "2026-04-21T17:00:00Z"
                        }]
                        """.formatted(programId, projectId) : "[]"
        );
    }

    private static String privateProjectPolicy(String projectId) {
        return """
                {
                  "projectId": "%s",
                  "workspaceId": "00000000-0000-0000-0000-000000000101",
                  "visibility": "private",
                  "workspaceAnonymousReadEnabled": true,
                  "publicReadEnabled": false,
                  "attachmentMaxUploadBytes": 10485760,
                  "attachmentMaxDownloadBytes": 52428800,
                  "attachmentAllowedContentTypes": "text/plain,application/json",
                  "exportMaxArtifactBytes": 52428800,
                  "exportAllowedContentTypes": "application/json,text/csv",
                  "importMaxParseBytes": 5242880,
                  "importAllowedContentTypes": "text/csv,application/json",
                  "workspaceCustomPolicy": false,
                  "customPolicy": false
                }
                """.formatted(projectId);
    }

    private static String publicProjectPolicy(String projectId) {
        return """
                {
                  "projectId": "%s",
                  "workspaceId": "00000000-0000-0000-0000-000000000101",
                  "visibility": "public",
                  "workspaceAnonymousReadEnabled": true,
                  "publicReadEnabled": true,
                  "attachmentMaxUploadBytes": 10485760,
                  "attachmentMaxDownloadBytes": 52428800,
                  "attachmentAllowedContentTypes": "text/plain,application/json",
                  "exportMaxArtifactBytes": 52428800,
                  "exportAllowedContentTypes": "application/json,text/csv",
                  "importMaxParseBytes": 5242880,
                  "importAllowedContentTypes": "text/csv,application/json",
                  "workspaceCustomPolicy": false,
                  "customPolicy": false
                }
                """.formatted(projectId);
    }

    private static void mockWorkspaceUsers(Page page, String workspaceId, AtomicBoolean userCreated, AtomicBoolean userRemoved) {
        page.route("**/api/v1/workspaces/" + workspaceId + "/users**", route -> {
            String method = route.request().method();
            if ("GET".equals(method)) {
                fulfillJson(route, 200, userCreated.get() && !userRemoved.get() ? """
                        [{
                          "membershipId": "00000000-0000-0000-0000-000000000202",
                          "workspaceId": "00000000-0000-0000-0000-000000000101",
                          "userId": "00000000-0000-0000-0000-000000000201",
                          "roleKey": "member",
                          "roleName": "Member",
                          "status": "active",
                          "email": "browser-user@example.test",
                          "username": "browser-user",
                          "displayName": "Browser User",
                          "accountType": "human",
                          "emailVerified": true,
                          "active": true
                        }]
                        """ : "[]");
                return;
            }
            if ("POST".equals(method)) {
                userCreated.set(true);
                userRemoved.set(false);
                fulfillJson(route, 201, """
                        {
                          "id": "00000000-0000-0000-0000-000000000201",
                          "email": "browser-user@example.test",
                          "username": "browser-user",
                          "displayName": "Browser User",
                          "accountType": "human",
                          "emailVerified": true
                        }
                        """);
                return;
            }
            if ("DELETE".equals(method)) {
                userRemoved.set(true);
                route.fulfill(new Route.FulfillOptions().setStatus(204));
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/users/*", route -> {
            if ("DELETE".equals(route.request().method())) {
                userRemoved.set(true);
                route.fulfill(new Route.FulfillOptions().setStatus(204));
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
    }

    private static void mockWorkspaceInvitations(Page page, String workspaceId, AtomicBoolean invitationCreated, AtomicBoolean invitationRevoked) {
        page.route("**/api/v1/workspaces/" + workspaceId + "/invitations**", route -> {
            String method = route.request().method();
            if ("GET".equals(method)) {
                fulfillJson(route, 200, invitationCreated.get() && !invitationRevoked.get() ? """
                        [{
                          "id": "00000000-0000-0000-0000-000000000301",
                          "workspaceId": "00000000-0000-0000-0000-000000000101",
                          "email": "browser-invite@example.test",
                          "roleKey": "member",
                          "roleName": "Member",
                          "status": "pending",
                          "expiresAt": "2026-04-28T17:00:00Z"
                        }]
                        """ : "[]");
                return;
            }
            if ("POST".equals(method)) {
                invitationCreated.set(true);
                invitationRevoked.set(false);
                fulfillJson(route, 201, """
                        {
                          "id": "00000000-0000-0000-0000-000000000301",
                          "workspaceId": "00000000-0000-0000-0000-000000000101",
                          "email": "browser-invite@example.test",
                          "token": "redacted-by-preview",
                          "status": "pending",
                          "expiresAt": "2026-04-28T17:00:00Z"
                        }
                        """);
                return;
            }
            if ("DELETE".equals(method)) {
                invitationRevoked.set(true);
                route.fulfill(new Route.FulfillOptions().setStatus(204));
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/invitations/*", route -> {
            if ("DELETE".equals(route.request().method())) {
                invitationRevoked.set(true);
                route.fulfill(new Route.FulfillOptions().setStatus(204));
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
    }

    private static void fulfillJson(Route route, int status, String body) {
        route.fulfill(new Route.FulfillOptions()
                .setStatus(status)
                .setContentType("application/json")
                .setBody(body));
    }

    private static String rolePermissionCatalog() {
        return """
                [
                  {
                    "id": "00000000-0000-0000-0000-000000000901",
                    "key": "workspace.read",
                    "name": "Read workspace",
                    "description": "Read workspace data.",
                    "category": "workspace"
                  },
                  {
                    "id": "00000000-0000-0000-0000-000000000902",
                    "key": "project.read",
                    "name": "Read project",
                    "description": "Read project data.",
                    "category": "project"
                  },
                  {
                    "id": "00000000-0000-0000-0000-000000000903",
                    "key": "work_item.read",
                    "name": "Read work items",
                    "description": "Read work items.",
                    "category": "work"
                  },
                  {
                    "id": "00000000-0000-0000-0000-000000000904",
                    "key": "work_item.update",
                    "name": "Update work items",
                    "description": "Update work items.",
                    "category": "work"
                  }
                ]
                """;
    }

    private static String roleJson(String roleId, String workspaceId, String projectId, String scope, String key, String name) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "projectId": %s,
                  "key": "%s",
                  "name": "%s",
                  "scope": "%s",
                  "description": "Browser role management test role",
                  "systemRole": true,
                  "status": "active",
                  "permissionKeys": ["%s.read", "work_item.read", "work_item.update"],
                  "impactSummary": {
                    "activeMembers": 1,
                    "pendingInvitations": 0,
                    "affectedUsers": 1,
                    "affectsCurrentUser": false
                  },
                  "createdAt": "2026-04-21T17:00:00Z",
                  "updatedAt": "2026-04-21T17:00:00Z"
                }
                """.formatted(
                roleId,
                workspaceId,
                projectId == null ? "null" : "\"" + projectId + "\"",
                key,
                name,
                scope,
                scope
        );
    }

    private static String roleVersionsJson(String roleId) {
        return """
                [{
                  "id": "00000000-0000-0000-0000-000000000910",
                  "roleId": "%s",
                  "versionNumber": 1,
                  "name": "Member",
                  "key": "member",
                  "scope": "workspace",
                  "systemRole": true,
                  "status": "active",
                  "permissionKeys": ["workspace.read", "work_item.read"],
                  "changeType": "baseline",
                  "changeNote": "Browser fixture baseline",
                  "createdAt": "2026-04-21T17:00:00Z"
                }]
                """.formatted(roleId);
    }

    private static Locator navigationLink(Locator navigation, String name) {
        return navigation.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions()
                .setName(name)
                .setExact(true));
    }
}
