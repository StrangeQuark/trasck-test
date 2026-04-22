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

    @Test
    void automationPageDrivesAsyncWorkerControlsThroughBrowserUi() {
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        String workspaceId = "00000000-0000-0000-0000-000000000101";
        String projectId = "00000000-0000-0000-0000-000000000501";
        String ruleId = "00000000-0000-0000-0000-000000000a01";
        String webhookId = "00000000-0000-0000-0000-000000000a02";
        String deliveryId = "00000000-0000-0000-0000-000000000a03";
        String emailDeliveryId = "00000000-0000-0000-0000-000000000a04";
        AtomicBoolean webhookCreated = new AtomicBoolean(false);
        AtomicBoolean ruleCreated = new AtomicBoolean(false);
        AtomicBoolean actionCreated = new AtomicBoolean(false);
        AtomicBoolean ruleQueued = new AtomicBoolean(false);
        AtomicBoolean queuedJobsRun = new AtomicBoolean(false);
        AtomicBoolean webhooksProcessed = new AtomicBoolean(false);
        AtomicBoolean emailsProcessed = new AtomicBoolean(false);
        AtomicBoolean workerSettingsSaved = new AtomicBoolean(false);
        AtomicBoolean workerRunsExported = new AtomicBoolean(false);
        AtomicBoolean workerRunsPruned = new AtomicBoolean(false);

        try (Playwright playwright = Playwright.create();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession session = BrowserSession.start(browser, config, "automation-async-ui")) {
            Page page = session.page();
            page.addInitScript("localStorage.setItem('trasck.workspaceId', '" + workspaceId + "');"
                    + "localStorage.setItem('trasck.projectId', '" + projectId + "');"
                    + "localStorage.setItem('trasck.apiBaseUrl', '" + config.frontendBaseUrl() + "');");
            mockCurrentUser(page);
            mockCsrf(page);
            mockAutomationPage(
                    page,
                    workspaceId,
                    projectId,
                    ruleId,
                    webhookId,
                    deliveryId,
                    emailDeliveryId,
                    webhookCreated,
                    ruleCreated,
                    actionCreated,
                    ruleQueued,
                    queuedJobsRun,
                    webhooksProcessed,
                    emailsProcessed,
                    workerSettingsSaved,
                    workerRunsExported,
                    workerRunsPruned
            );

            page.navigate("/automation");

            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Webhooks"))).isVisible();
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Execution"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load")).click();
            assertThat(page.getByText("Browser Automation Webhook").first()).isVisible();
            assertThat(page.getByText("Browser Automation Rule").first()).isVisible();

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create webhook")).click();
            assertTrue(webhookCreated.get(), "Create webhook should call the frontend service");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create rule")).click();
            assertTrue(ruleCreated.get(), "Create rule should call the frontend service");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Use webhook")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add action")).click();
            assertTrue(actionCreated.get(), "Add action should call the frontend service");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Queue rule")).click();
            assertTrue(ruleQueued.get(), "Queue rule should call the frontend service");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run jobs")).click();
            assertTrue(queuedJobsRun.get(), "Run jobs should call the automation worker endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run deliveries")).click();
            assertTrue(webhooksProcessed.get(), "Run deliveries should call the webhook worker endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run emails")).click();
            assertTrue(emailsProcessed.get(), "Run emails should call the email worker endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save worker settings")).click();
            assertTrue(workerSettingsSaved.get(), "Save worker settings should call the settings endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Export runs")).click();
            assertTrue(workerRunsExported.get(), "Export runs should call the worker-run export endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Prune runs")).click();
            assertTrue(workerRunsPruned.get(), "Prune runs should call the worker-run prune endpoint");

            session.screenshot();
            session.assertNoConsoleErrors();
        }
    }

    @Test
    void agentsPageDrivesProviderProfileTaskAndDispatchAttemptControlsThroughBrowserUi() {
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        String workspaceId = "00000000-0000-0000-0000-000000000101";
        String projectId = "00000000-0000-0000-0000-000000000501";
        String providerId = "00000000-0000-0000-0000-000000000c01";
        String profileId = "00000000-0000-0000-0000-000000000c02";
        String repositoryId = "00000000-0000-0000-0000-000000000c03";
        String workItemId = "00000000-0000-0000-0000-000000000c04";
        String taskId = "00000000-0000-0000-0000-000000000c05";
        AtomicBoolean providerCreated = new AtomicBoolean(false);
        AtomicBoolean profileCreated = new AtomicBoolean(false);
        AtomicBoolean repositoryConnected = new AtomicBoolean(false);
        AtomicBoolean runtimePreviewed = new AtomicBoolean(false);
        AtomicBoolean taskAssigned = new AtomicBoolean(false);
        AtomicBoolean taskLoaded = new AtomicBoolean(false);
        AtomicBoolean taskRetried = new AtomicBoolean(false);
        AtomicBoolean taskAccepted = new AtomicBoolean(false);
        AtomicBoolean taskCanceled = new AtomicBoolean(false);
        AtomicBoolean attemptsExported = new AtomicBoolean(false);
        AtomicBoolean attemptsPruned = new AtomicBoolean(false);

        try (Playwright playwright = Playwright.create();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession session = BrowserSession.start(browser, config, "agents-ui")) {
            Page page = session.page();
            page.addInitScript("localStorage.setItem('trasck.workspaceId', '" + workspaceId + "');"
                    + "localStorage.setItem('trasck.projectId', '" + projectId + "');"
                    + "localStorage.setItem('trasck.apiBaseUrl', '" + config.frontendBaseUrl() + "');");
            mockCurrentUser(page);
            mockCsrf(page);
            mockAgentsPage(
                    page,
                    workspaceId,
                    projectId,
                    providerId,
                    profileId,
                    repositoryId,
                    workItemId,
                    taskId,
                    providerCreated,
                    profileCreated,
                    repositoryConnected,
                    runtimePreviewed,
                    taskAssigned,
                    taskLoaded,
                    taskRetried,
                    taskAccepted,
                    taskCanceled,
                    attemptsExported,
                    attemptsPruned
            );

            page.navigate("/agents");

            assertThat(page.locator("xpath=//h2[normalize-space()='Provider']").first()).isVisible();
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Agent Records"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load")).last().click();
            assertThat(page.getByText("Browser Agent Provider").first()).isVisible();
            assertThat(page.getByText("Browser Agent Profile").first()).isVisible();
            assertThat(page.getByText("Browser agent story").first()).isVisible();

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create provider")).click();
            assertTrue(providerCreated.get(), "Create provider should call the agent provider endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create profile")).click();
            assertTrue(profileCreated.get(), "Create profile should call the agent profile endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
            assertTrue(repositoryConnected.get(), "Connect should call the repository endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview runtime")).click();
            assertTrue(runtimePreviewed.get(), "Preview runtime should call the runtime preview endpoint");
            assertThat(page.getByText("trasck.agent-runtime-preview.v1").first()).isVisible();

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign")).click();
            assertTrue(taskAssigned.get(), "Assign should call the work-item agent assignment endpoint");
            assertThat(page.getByText(taskId).first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load")).first().click();
            assertTrue(taskLoaded.get(), "Task Load should call the agent task detail endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Retry")).click();
            assertTrue(taskRetried.get(), "Retry should call the agent task retry endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Accept")).click();
            assertTrue(taskAccepted.get(), "Accept should call the agent task accept endpoint");
            page.locator("button[title='Cancel']").click();
            assertTrue(taskCanceled.get(), "Cancel should call the agent task cancel endpoint");

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Attempts")).click();
            assertThat(page.getByText("dispatch").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Export attempts")).click();
            assertTrue(attemptsExported.get(), "Export attempts should call the dispatch-attempt export endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Prune attempts")).click();
            assertTrue(attemptsPruned.get(), "Prune attempts should call the dispatch-attempt prune endpoint");

            session.screenshot();
            session.assertNoConsoleErrors();
        }
    }

    @Test
    void authTokenAndSystemAdminScreensDriveSecurityControlsThroughBrowserUi() {
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        String workspaceId = "00000000-0000-0000-0000-000000000101";
        String adminUserId = "00000000-0000-0000-0000-000000000001";
        String grantedUserId = "00000000-0000-0000-0000-000000000a11";
        AtomicBoolean loggedIn = new AtomicBoolean(false);
        AtomicBoolean loggedOut = new AtomicBoolean(false);
        AtomicBoolean personalTokenCreated = new AtomicBoolean(false);
        AtomicBoolean serviceTokenCreated = new AtomicBoolean(false);
        AtomicBoolean systemAdminGranted = new AtomicBoolean(false);
        AtomicBoolean systemAdminRevoked = new AtomicBoolean(false);

        try (Playwright playwright = Playwright.create();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession session = BrowserSession.start(browser, config, "auth-token-system-ui")) {
            Page page = session.page();
            page.addInitScript("localStorage.setItem('trasck.workspaceId', '" + workspaceId + "');"
                    + "localStorage.setItem('trasck.apiBaseUrl', '" + config.frontendBaseUrl() + "');");
            mockCurrentUser(page);
            mockCsrf(page);
            mockAuthPage(page, adminUserId, loggedIn, loggedOut);
            mockTokenAdminPage(page, workspaceId, personalTokenCreated, serviceTokenCreated);
            mockSystemAdminPage(page, adminUserId, grantedUserId, systemAdminGranted, systemAdminRevoked);

            page.navigate("/auth");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign In"))).isVisible();
            page.getByLabel("Identifier").fill("admin@example.test");
            page.getByLabel("Password").fill("correct-horse-battery-staple");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
            assertTrue(loggedIn.get(), "Login button should call the auth login endpoint");
            assertThat(page.getByText("Browser Admin").first()).isVisible();
            page.locator("button[title='Logout']").click();
            assertTrue(loggedOut.get(), "Logout icon should call the auth logout endpoint");

            page.navigate("/tokens");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Personal Token"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load")).click();
            assertThat(page.getByText("Browser Personal Token").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create token")).click();
            assertTrue(personalTokenCreated.get(), "Create token should call the personal token endpoint");
            page.getByLabel("Role ID").fill("00000000-0000-0000-0000-000000000401");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create service token")).click();
            assertTrue(serviceTokenCreated.get(), "Create service token should call the service token endpoint");
            assertThat(page.getByText("pt_browser_created").first()).isVisible();

            page.navigate("/system");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("System Admins"))).isVisible();
            page.getByLabel("User ID").fill(grantedUserId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Grant")).click();
            assertTrue(systemAdminGranted.get(), "Grant should call the system-admin endpoint");
            assertThat(page.getByText("Granted Browser Admin").first()).isVisible();
            page.locator("button[title='Revoke system admin']").last().click();
            assertTrue(systemAdminRevoked.get(), "Revoke should call the system-admin revoke endpoint");

            session.screenshot();
            session.assertNoConsoleErrors();
        }
    }

    @Test
    void importsPageDrivesReviewExportAndWorkerControlsThroughBrowserUi() {
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        String workspaceId = "00000000-0000-0000-0000-000000000101";
        String projectId = "00000000-0000-0000-0000-000000000501";
        String importJobId = "00000000-0000-0000-0000-000000000d01";
        String templateId = "00000000-0000-0000-0000-000000000d02";
        String presetId = "00000000-0000-0000-0000-000000000d03";
        String recordId = "00000000-0000-0000-0000-000000000d04";
        String conflictJobId = "00000000-0000-0000-0000-000000000d05";
        String exportJobId = "00000000-0000-0000-0000-000000000d06";
        AtomicBoolean settingSaved = new AtomicBoolean(false);
        AtomicBoolean sampleCreated = new AtomicBoolean(false);
        AtomicBoolean jobCreated = new AtomicBoolean(false);
        AtomicBoolean parsed = new AtomicBoolean(false);
        AtomicBoolean presetCreated = new AtomicBoolean(false);
        AtomicBoolean templateCreated = new AtomicBoolean(false);
        AtomicBoolean materialized = new AtomicBoolean(false);
        AtomicBoolean conflictWorkerProcessed = new AtomicBoolean(false);
        AtomicBoolean reviewExportQueued = new AtomicBoolean(false);
        AtomicBoolean reviewExportsProcessed = new AtomicBoolean(false);
        AtomicBoolean versionExportCreated = new AtomicBoolean(false);

        try (Playwright playwright = Playwright.create();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession session = BrowserSession.start(browser, config, "imports-ui")) {
            Page page = session.page();
            page.addInitScript("localStorage.setItem('trasck.workspaceId', '" + workspaceId + "');"
                    + "localStorage.setItem('trasck.projectId', '" + projectId + "');"
                    + "localStorage.setItem('trasck.apiBaseUrl', '" + config.frontendBaseUrl() + "');");
            mockCurrentUser(page);
            mockCsrf(page);
            mockImportsPage(
                    page,
                    workspaceId,
                    projectId,
                    importJobId,
                    templateId,
                    presetId,
                    recordId,
                    conflictJobId,
                    exportJobId,
                    settingSaved,
                    sampleCreated,
                    jobCreated,
                    parsed,
                    presetCreated,
                    templateCreated,
                    materialized,
                    conflictWorkerProcessed,
                    reviewExportQueued,
                    reviewExportsProcessed,
                    versionExportCreated
            );

            page.navigate("/imports");
            assertThat(page.locator("xpath=//h2[normalize-space()='Import Job']").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            assertThat(page.getByText("Browser imported story").first()).isVisible();

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save setting")).click();
            assertTrue(settingSaved.get(), "Save setting should call import settings");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create sample job")).click();
            assertTrue(sampleCreated.get(), "Create sample job should call sample endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create preset")).click();
            assertTrue(presetCreated.get(), "Create preset should call transform preset endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create template")).click();
            assertTrue(templateCreated.get(), "Create template should call mapping template endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create job")).click();
            assertTrue(jobCreated.get(), "Create job should call import job endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Parse")).click();
            assertTrue(parsed.get(), "Parse should call import parse endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Materialize")).click();
            assertTrue(materialized.get(), "Materialize should call import materialization endpoint");

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Process queued")).click();
            assertTrue(conflictWorkerProcessed.get(), "Process queued should call conflict worker endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create export artifact")).click();
            assertTrue(versionExportCreated.get(), "Create export artifact should call job diff export endpoint");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("CSV")).first().click();
            assertTrue(reviewExportQueued.get(), "CSV export should queue an import review export");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Process review exports")).click();
            assertTrue(reviewExportsProcessed.get(), "Process review exports should call review export worker endpoint");
            assertThat(page.getByText("Browser conflict job").first()).isVisible();

            session.screenshot();
            session.assertNoConsoleErrors();
        }
    }

    private static void mockAuthPage(Page page, String userId, AtomicBoolean loggedIn, AtomicBoolean loggedOut) {
        page.route("**/api/v1/auth/login", route -> {
            loggedIn.set(true);
            fulfillJson(route, 200, """
                    {
                      "user": {
                        "id": "%s",
                        "email": "admin@example.test",
                        "username": "admin",
                        "displayName": "Browser Admin",
                        "accountType": "human",
                        "emailVerified": true
                      },
                      "tokenType": "Bearer",
                      "accessToken": "browser-access-token",
                      "expiresAt": "2026-04-21T23:00:00Z"
                    }
                    """.formatted(userId));
        });
        page.route("**/api/v1/auth/logout", route -> {
            loggedOut.set(true);
            route.fulfill(new Route.FulfillOptions().setStatus(204));
        });
    }

    private static void mockTokenAdminPage(
            Page page,
            String workspaceId,
            AtomicBoolean personalTokenCreated,
            AtomicBoolean serviceTokenCreated
    ) {
        page.route("**/api/v1/auth/tokens/personal", route -> {
            if ("POST".equals(route.request().method())) {
                personalTokenCreated.set(true);
                fulfillJson(route, 201, apiTokenJson(
                        "00000000-0000-0000-0000-000000000a21",
                        null,
                        "personal",
                        "Browser Created Personal Token",
                        "pt_browser_created",
                        "pt_browser_created_secret"
                ));
                return;
            }
            fulfillJson(route, 200, "[" + apiTokenJson(
                    "00000000-0000-0000-0000-000000000a20",
                    null,
                    "personal",
                    "Browser Personal Token",
                    "pt_browser",
                    null
            ) + "]");
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/service-tokens", route -> {
            if ("POST".equals(route.request().method())) {
                serviceTokenCreated.set(true);
                fulfillJson(route, 201, apiTokenJson(
                        "00000000-0000-0000-0000-000000000a23",
                        workspaceId,
                        "service",
                        "Browser Created Service Token",
                        "st_browser_created",
                        "st_browser_created_secret"
                ));
                return;
            }
            fulfillJson(route, 200, "[" + apiTokenJson(
                    "00000000-0000-0000-0000-000000000a22",
                    workspaceId,
                    "service",
                    "Browser Service Token",
                    "st_browser",
                    null
            ) + "]");
        });
    }

    private static void mockSystemAdminPage(
            Page page,
            String adminUserId,
            String grantedUserId,
            AtomicBoolean systemAdminGranted,
            AtomicBoolean systemAdminRevoked
    ) {
        page.route("**/api/v1/system-admins", route -> {
            if ("POST".equals(route.request().method())) {
                systemAdminGranted.set(true);
                fulfillJson(route, 201, systemAdminJson(
                        "00000000-0000-0000-0000-000000000a32",
                        grantedUserId,
                        "granted-browser-admin@example.test",
                        "Granted Browser Admin",
                        true
                ));
                return;
            }
            fulfillJson(route, 200, systemAdminGranted.get()
                    ? "[" + systemAdminJson("00000000-0000-0000-0000-000000000a31", adminUserId, "admin@example.test", "Browser Admin", true)
                    + "," + systemAdminJson("00000000-0000-0000-0000-000000000a32", grantedUserId, "granted-browser-admin@example.test", "Granted Browser Admin", !systemAdminRevoked.get()) + "]"
                    : "[" + systemAdminJson("00000000-0000-0000-0000-000000000a31", adminUserId, "admin@example.test", "Browser Admin", true) + "]");
        });
        page.route("**/api/v1/system-admins/" + grantedUserId, route -> {
            if ("DELETE".equals(route.request().method())) {
                systemAdminRevoked.set(true);
                route.fulfill(new Route.FulfillOptions().setStatus(204));
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(405));
        });
    }

    private static void mockImportsPage(
            Page page,
            String workspaceId,
            String projectId,
            String importJobId,
            String templateId,
            String presetId,
            String recordId,
            String conflictJobId,
            String exportJobId,
            AtomicBoolean settingSaved,
            AtomicBoolean sampleCreated,
            AtomicBoolean jobCreated,
            AtomicBoolean parsed,
            AtomicBoolean presetCreated,
            AtomicBoolean templateCreated,
            AtomicBoolean materialized,
            AtomicBoolean conflictWorkerProcessed,
            AtomicBoolean reviewExportQueued,
            AtomicBoolean reviewExportsProcessed,
            AtomicBoolean versionExportCreated
    ) {
        String runId = "00000000-0000-0000-0000-000000000d07";
        String versionId = "00000000-0000-0000-0000-000000000d08";
        page.route("**/api/v1/workspaces/" + workspaceId + "/import-settings", route -> {
            if ("PATCH".equals(route.request().method())) {
                settingSaved.set(true);
            }
            fulfillJson(route, 200, importSettingsJson(workspaceId));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/import-samples", route -> fulfillJson(route, 200, """
                [{
                  "key": "csv",
                  "label": "CSV sample",
                  "provider": "csv",
                  "sourceType": "row",
                  "description": "Browser CSV sample"
                }]
                """));
        page.route("**/api/v1/workspaces/" + workspaceId + "/import-samples/csv/jobs", route -> {
            sampleCreated.set(true);
            fulfillJson(route, 201, """
                    {
                      "sample": {"key": "csv", "label": "CSV sample", "provider": "csv", "sourceType": "row"},
                      "importJob": %s,
                      "parse": {"importJobId": "%s", "provider": "csv", "recordsParsed": 1, "records": [%s]},
                      "transformPreset": %s,
                      "mappingTemplate": %s
                    }
                    """.formatted(
                    importJobJson(importJobId, workspaceId, "parsed"),
                    importJobId,
                    importRecordJson(recordId, importJobId, "row", "CSV-1", "pending", null),
                    transformPresetJson(presetId, workspaceId),
                    mappingTemplateJson(templateId, workspaceId, projectId, presetId)
            ));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/import-jobs", route -> {
            if ("POST".equals(route.request().method())) {
                jobCreated.set(true);
                fulfillJson(route, 201, importJobJson(importJobId, workspaceId, "queued"));
                return;
            }
            fulfillJson(route, 200, "[" + importJobJson(importJobId, workspaceId, materialized.get() ? "running" : "queued") + "]");
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/import-transform-presets", route -> {
            if ("POST".equals(route.request().method())) {
                presetCreated.set(true);
                fulfillJson(route, 201, transformPresetJson(presetId, workspaceId));
                return;
            }
            fulfillJson(route, 200, "[" + transformPresetJson(presetId, workspaceId) + "]");
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/import-mapping-templates", route -> {
            if ("POST".equals(route.request().method())) {
                templateCreated.set(true);
                fulfillJson(route, 201, mappingTemplateJson(templateId, workspaceId, projectId, presetId));
                return;
            }
            fulfillJson(route, 200, "[" + mappingTemplateJson(templateId, workspaceId, projectId, presetId) + "]");
        });
        page.route("**/api/v1/import-transform-presets/" + presetId + "/versions", route -> fulfillJson(route, 200, """
                [{
                  "id": "%s",
                  "presetId": "%s",
                  "workspaceId": "%s",
                  "version": 1,
                  "name": "Browser Import Preset",
                  "description": "Browser import preset",
                  "transformationConfig": {"title": ["trim"]},
                  "enabled": true,
                  "changeType": "created",
                  "createdAt": "2026-04-21T20:00:00Z"
                }]
                """.formatted(versionId, presetId, workspaceId)));
        page.route("**/api/v1/import-jobs/" + importJobId + "/parse", route -> {
            parsed.set(true);
            fulfillJson(route, 200, """
                    {
                      "importJobId": "%s",
                      "provider": "csv",
                      "recordsParsed": 1,
                      "records": [%s]
                    }
                    """.formatted(importJobId, importRecordJson(recordId, importJobId, "row", "CSV-1", "pending", null)));
        });
        page.route("**/api/v1/import-jobs/" + importJobId + "/materialize", route -> {
            materialized.set(true);
            fulfillJson(route, 200, """
                    {
                      "materializationRunId": "%s",
                      "importJobId": "%s",
                      "mappingTemplateId": "%s",
                      "projectId": "%s",
                      "recordsProcessed": 1,
                      "created": 1,
                      "updated": 0,
                      "failed": 0,
                      "skipped": 0,
                      "conflicts": 0,
                      "records": [%s]
                    }
                    """.formatted(runId, importJobId, templateId, projectId, importRecordJson(recordId, importJobId, "row", "CSV-1", "imported", null)));
        });
        page.route("**/api/v1/import-jobs/" + importJobId + "/records**", route -> fulfillJson(route, 200,
                "[" + importRecordJson(recordId, importJobId, "row", "CSV-1", materialized.get() ? "imported" : "pending", null) + "]"));
        page.route("**/api/v1/import-jobs/" + importJobId + "/conflicts", route -> fulfillJson(route, 200,
                "[" + importRecordJson(recordId, importJobId, "row", "CSV-1", "conflict", "open") + "]"));
        page.route("**/api/v1/import-jobs/" + importJobId + "/conflict-resolution-jobs", route -> fulfillJson(route, 200,
                "[" + conflictResolutionJobJson(conflictJobId, workspaceId, importJobId, conflictWorkerProcessed.get() ? "completed" : "queued") + "]"));
        page.route("**/api/v1/workspaces/" + workspaceId + "/import-conflict-resolution-jobs**", route -> {
            if ("POST".equals(route.request().method()) && route.request().url().contains("/process")) {
                conflictWorkerProcessed.set(true);
                fulfillJson(route, 200, """
                        {
                          "workspaceId": "%s",
                          "processed": 1,
                          "completed": 1,
                          "failed": 0,
                          "jobs": [%s]
                        }
                        """.formatted(workspaceId, conflictResolutionJobJson(conflictJobId, workspaceId, importJobId, "completed")));
                return;
            }
            fulfillJson(route, 200, "[" + conflictResolutionJobJson(conflictJobId, workspaceId, importJobId, conflictWorkerProcessed.get() ? "completed" : "queued") + "]");
        });
        page.route("**/api/v1/import-jobs/" + importJobId + "/version-diffs", route -> fulfillJson(route, 200, importJobDiffJson(importJobId, workspaceId)));
        page.route("**/api/v1/import-jobs/" + importJobId + "/version-diffs/export-jobs", route -> {
            versionExportCreated.set(true);
            fulfillJson(route, 201, exportJobJson(exportJobId, workspaceId, "import_job_version_diffs", "completed"));
        });
        page.route("**/api/v1/import-job-records/" + recordId + "/versions", route -> fulfillJson(route, 200, "[]"));
        page.route("**/api/v1/import-job-records/" + recordId + "/version-diffs", route -> fulfillJson(route, 200, "[]"));
        page.route("**/api/v1/import-jobs/" + importJobId + "/materialization-runs", route -> fulfillJson(route, 200, """
                [{
                  "id": "%s",
                  "workspaceId": "%s",
                  "importJobId": "%s",
                  "mappingTemplateId": "%s",
                  "projectId": "%s",
                  "recordsProcessed": 1,
                  "recordsCreated": 1,
                  "recordsUpdated": 0,
                  "recordsFailed": 0,
                  "recordsSkipped": 0,
                  "recordsConflicted": 0,
                  "updateExisting": false,
                  "createdAt": "2026-04-21T20:00:00Z"
                }]
                """.formatted(runId, workspaceId, importJobId, templateId, projectId)));
        page.route("**/api/v1/workspaces/" + workspaceId + "/export-jobs**", route -> fulfillJson(route, 200, """
                {
                  "items": [%s],
                  "nextCursor": null,
                  "hasMore": false,
                  "limit": 20
                }
                """.formatted(exportJobJson(exportJobId, workspaceId, "import_job_version_diffs", "completed"))));
        page.route("**/api/v1/workspaces/" + workspaceId + "/import-review/export-jobs", route -> {
            reviewExportQueued.set(true);
            fulfillJson(route, 201, exportJobJson(exportJobId, workspaceId, "import_conflict_resolution_jobs", "queued"));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/import-review/export-jobs/process", route -> {
            reviewExportsProcessed.set(true);
            fulfillJson(route, 200, """
                    {
                      "workspaceId": "%s",
                      "requestedLimit": 10,
                      "processed": 1,
                      "completed": 1,
                      "failed": 0,
                      "jobs": [%s]
                    }
                    """.formatted(workspaceId, exportJobJson(exportJobId, workspaceId, "import_conflict_resolution_jobs", "completed")));
        });
        page.route("**/api/v1/reports/projects/" + projectId + "/imports/completions**", route -> fulfillJson(route, 200, importCompletionJson()));
        page.route("**/api/v1/reports/workspaces/" + workspaceId + "/imports/completions**", route -> fulfillJson(route, 200, importCompletionJson()));
        page.route("**/api/v1/import-jobs/" + importJobId, route -> fulfillJson(route, 200, importJobJson(importJobId, workspaceId, materialized.get() ? "running" : "queued")));
    }

    private static void mockAutomationPage(
            Page page,
            String workspaceId,
            String projectId,
            String ruleId,
            String webhookId,
            String deliveryId,
            String emailDeliveryId,
            AtomicBoolean webhookCreated,
            AtomicBoolean ruleCreated,
            AtomicBoolean actionCreated,
            AtomicBoolean ruleQueued,
            AtomicBoolean queuedJobsRun,
            AtomicBoolean webhooksProcessed,
            AtomicBoolean emailsProcessed,
            AtomicBoolean workerSettingsSaved,
            AtomicBoolean workerRunsExported,
            AtomicBoolean workerRunsPruned
    ) {
        page.route("**/api/v1/workspaces/" + workspaceId + "/notifications**", route -> fulfillJson(route, 200, """
                {
                  "items": [],
                  "nextCursor": null,
                  "hasMore": false,
                  "limit": 25
                }
                """));
        page.route("**/api/v1/workspaces/" + workspaceId + "/notification-preferences", route -> fulfillJson(route, 200, "[]"));
        page.route("**/api/v1/workspaces/" + workspaceId + "/notification-defaults", route -> fulfillJson(route, 200, "[]"));
        page.route("**/api/v1/projects/" + projectId + "/work-items**", route -> fulfillJson(route, 200, """
                {
                  "items": [{
                    "id": "00000000-0000-0000-0000-000000000b01",
                    "projectId": "%s",
                    "key": "BAT-1",
                    "title": "Browser automation story"
                  }],
                  "nextCursor": null,
                  "hasMore": false,
                  "limit": 50
                }
                """.formatted(projectId)));
        page.route("**/api/v1/workspaces/" + workspaceId + "/automation-worker-settings", route -> {
            if ("PATCH".equals(route.request().method())) {
                workerSettingsSaved.set(true);
            }
            fulfillJson(route, 200, automationWorkerSettingsJson(workspaceId));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/automation-worker-runs**", route -> {
            String method = route.request().method();
            String url = route.request().url();
            if ("POST".equals(method) && url.contains("/export")) {
                workerRunsExported.set(true);
                fulfillJson(route, 200, automationWorkerRetentionJson(workspaceId, "webhook", 0));
                return;
            }
            if ("POST".equals(method) && url.contains("/prune")) {
                workerRunsPruned.set(true);
                fulfillJson(route, 200, automationWorkerRetentionJson(workspaceId, "webhook", 1));
                return;
            }
            fulfillJson(route, 200, """
                    [{
                      "id": "00000000-0000-0000-0000-000000000b02",
                      "workspaceId": "%s",
                      "workerType": "webhook",
                      "triggerType": "manual",
                      "status": "succeeded",
                      "dryRun": false,
                      "requestedLimit": 5,
                      "maxAttempts": 2,
                      "processedCount": 1,
                      "successCount": 1,
                      "failureCount": 0,
                      "deadLetterCount": 0,
                      "metadata": {},
                      "startedAt": "2026-04-21T20:00:00Z",
                      "finishedAt": "2026-04-21T20:00:01Z"
                    }]
                    """.formatted(workspaceId));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/automation-worker-health**", route -> fulfillJson(route, 200, """
                [{
                  "workspaceId": "%s",
                  "workerType": "webhook",
                  "lastStatus": "succeeded",
                  "lastStartedAt": "2026-04-21T20:00:00Z",
                  "lastFinishedAt": "2026-04-21T20:00:01Z",
                  "lastRunId": "00000000-0000-0000-0000-000000000b02",
                  "consecutiveFailures": 0,
                  "updatedAt": "2026-04-21T20:00:01Z"
                }]
                """.formatted(workspaceId)));
        page.route("**/api/v1/workspaces/" + workspaceId + "/webhook-deliveries/process", route -> {
            webhooksProcessed.set(true);
            fulfillJson(route, 200, """
                    {
                      "workspaceId": "%s",
                      "processed": 1,
                      "delivered": 1,
                      "failed": 0,
                      "deadLettered": 0,
                      "deliveries": [%s]
                    }
                    """.formatted(workspaceId, webhookDeliveryJson(deliveryId, webhookId, "delivered")));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/email-deliveries/process", route -> {
            emailsProcessed.set(true);
            fulfillJson(route, 200, """
                    {
                      "workspaceId": "%s",
                      "processed": 1,
                      "sent": 1,
                      "failed": 0,
                      "deadLettered": 0,
                      "deliveries": [%s]
                    }
                    """.formatted(workspaceId, emailDeliveryJson(emailDeliveryId, workspaceId, "sent")));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/email-deliveries**", route -> fulfillJson(route, 200, "[" + emailDeliveryJson(emailDeliveryId, workspaceId, "queued") + "]"));
        page.route("**/api/v1/workspaces/" + workspaceId + "/webhooks", route -> {
            String method = route.request().method();
            if ("POST".equals(method)) {
                webhookCreated.set(true);
                fulfillJson(route, 201, webhookJson(webhookId, workspaceId));
                return;
            }
            fulfillJson(route, 200, "[" + webhookJson(webhookId, workspaceId) + "]");
        });
        page.route("**/api/v1/webhooks/" + webhookId + "/deliveries", route -> fulfillJson(route, 200, "[" + webhookDeliveryJson(deliveryId, webhookId, "queued") + "]"));
        page.route("**/api/v1/workspaces/" + workspaceId + "/automation-rules", route -> {
            String method = route.request().method();
            if ("POST".equals(method)) {
                ruleCreated.set(true);
                fulfillJson(route, 201, automationRuleJson(ruleId, workspaceId, projectId));
                return;
            }
            fulfillJson(route, 200, "[" + automationRuleJson(ruleId, workspaceId, projectId) + "]");
        });
        page.route("**/api/v1/automation-rules/" + ruleId + "/actions", route -> {
            actionCreated.set(true);
            fulfillJson(route, 201, """
                    {
                      "id": "00000000-0000-0000-0000-000000000b03",
                      "ruleId": "%s",
                      "actionType": "webhook",
                      "executionMode": "async",
                      "config": {"webhookId": "%s"},
                      "position": 1
                    }
                    """.formatted(ruleId, webhookId));
        });
        page.route("**/api/v1/automation-rules/" + ruleId + "/execute", route -> {
            ruleQueued.set(true);
            fulfillJson(route, 200, automationJobJson("00000000-0000-0000-0000-000000000b04", ruleId, workspaceId, "queued"));
        });
        page.route("**/api/v1/automation-rules/" + ruleId + "/jobs", route -> fulfillJson(route, 200,
                "[" + automationJobJson("00000000-0000-0000-0000-000000000b04", ruleId, workspaceId, "queued") + "]"));
        page.route("**/api/v1/workspaces/" + workspaceId + "/automation-jobs/run-queued", route -> {
            queuedJobsRun.set(true);
            fulfillJson(route, 200, """
                    {
                      "workspaceId": "%s",
                      "processed": 1,
                      "succeeded": 1,
                      "failed": 0,
                      "jobs": [%s]
                    }
                    """.formatted(workspaceId, automationJobJson("00000000-0000-0000-0000-000000000b04", ruleId, workspaceId, "succeeded")));
        });
    }

    private static void mockAgentsPage(
            Page page,
            String workspaceId,
            String projectId,
            String providerId,
            String profileId,
            String repositoryId,
            String workItemId,
            String taskId,
            AtomicBoolean providerCreated,
            AtomicBoolean profileCreated,
            AtomicBoolean repositoryConnected,
            AtomicBoolean runtimePreviewed,
            AtomicBoolean taskAssigned,
            AtomicBoolean taskLoaded,
            AtomicBoolean taskRetried,
            AtomicBoolean taskAccepted,
            AtomicBoolean taskCanceled,
            AtomicBoolean attemptsExported,
            AtomicBoolean attemptsPruned
    ) {
        page.route("**/api/v1/workspaces/" + workspaceId + "/agent-providers", route -> {
            if ("POST".equals(route.request().method())) {
                providerCreated.set(true);
                fulfillJson(route, 201, agentProviderJson(providerId, workspaceId));
                return;
            }
            fulfillJson(route, 200, "[" + agentProviderJson(providerId, workspaceId) + "]");
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/agents", route -> {
            if ("POST".equals(route.request().method())) {
                profileCreated.set(true);
                fulfillJson(route, 201, agentProfileJson(profileId, providerId, workspaceId));
                return;
            }
            fulfillJson(route, 200, "[" + agentProfileJson(profileId, providerId, workspaceId) + "]");
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/repository-connections", route -> {
            if ("POST".equals(route.request().method())) {
                repositoryConnected.set(true);
                fulfillJson(route, 201, repositoryConnectionJson(repositoryId, workspaceId, projectId));
                return;
            }
            fulfillJson(route, 200, "[" + repositoryConnectionJson(repositoryId, workspaceId, projectId) + "]");
        });
        page.route("**/api/v1/projects/" + projectId + "/work-items**", route -> fulfillJson(route, 200, """
                {
                  "items": [{
                    "id": "%s",
                    "workspaceId": "%s",
                    "projectId": "%s",
                    "key": "BAG-1",
                    "title": "Browser agent story",
                    "typeKey": "story",
                    "statusKey": "open"
                  }],
                  "nextCursor": null,
                  "hasMore": false,
                  "limit": 50
                }
                """.formatted(workItemId, workspaceId, projectId)));
        page.route("**/api/v1/agent-providers/" + providerId + "/runtime-preview", route -> {
            runtimePreviewed.set(true);
            fulfillJson(route, 200, """
                    {
                      "providerId": "%s",
                      "providerKey": "browser-agent-provider",
                      "providerType": "generic_worker",
                      "dispatchMode": "polling",
                      "runtimeMode": "provider_native",
                      "transport": "polling",
                      "externalExecutionEnabled": false,
                      "valid": true,
                      "errors": [],
                      "payload": {
                        "protocolVersion": "trasck.agent-runtime-preview.v1",
                        "action": "dispatched",
                        "agentTaskId": "%s"
                      }
                    }
                    """.formatted(providerId, taskId));
        });
        page.route("**/api/v1/work-items/" + workItemId + "/assign-agent", route -> {
            taskAssigned.set(true);
            fulfillJson(route, 201, agentTaskJson(taskId, workspaceId, workItemId, profileId, providerId, "running"));
        });
        page.route("**/api/v1/agent-tasks/" + taskId, route -> {
            taskLoaded.set(true);
            fulfillJson(route, 200, agentTaskJson(taskId, workspaceId, workItemId, profileId, providerId, "running"));
        });
        page.route("**/api/v1/agent-tasks/" + taskId + "/retry", route -> {
            taskRetried.set(true);
            fulfillJson(route, 200, agentTaskJson(taskId, workspaceId, workItemId, profileId, providerId, "running"));
        });
        page.route("**/api/v1/agent-tasks/" + taskId + "/accept-result", route -> {
            taskAccepted.set(true);
            fulfillJson(route, 200, agentTaskJson(taskId, workspaceId, workItemId, profileId, providerId, "completed"));
        });
        page.route("**/api/v1/agent-tasks/" + taskId + "/cancel", route -> {
            taskCanceled.set(true);
            fulfillJson(route, 200, agentTaskJson(taskId, workspaceId, workItemId, profileId, providerId, "canceled"));
        });
        page.route("**/api/v1/workspaces/" + workspaceId + "/agent-dispatch-attempts**", route -> {
            String method = route.request().method();
            String url = route.request().url();
            if ("POST".equals(method) && url.contains("/export")) {
                attemptsExported.set(true);
                fulfillJson(route, 201, """
                        {
                          "id": "00000000-0000-0000-0000-000000000c07",
                          "workspaceId": "%s",
                          "exportType": "agent_dispatch_attempts",
                          "status": "completed",
                          "filename": "agent-dispatch-attempts-browser.json",
                          "contentType": "application/json",
                          "sizeBytes": 512,
                          "checksum": "sha256:browser",
                          "requestPayload": {"agentTaskId": "%s"}
                        }
                        """.formatted(workspaceId, taskId));
                return;
            }
            if ("POST".equals(method) && url.contains("/prune")) {
                attemptsPruned.set(true);
                fulfillJson(route, 200, """
                        {
                          "workspaceId": "%s",
                          "retentionDays": 30,
                          "attemptsEligible": 1,
                          "attemptsIncluded": 1,
                          "attemptsPruned": 0,
                          "attempts": [%s]
                        }
                        """.formatted(workspaceId, agentDispatchAttemptJson(taskId, providerId, profileId, workItemId)));
                return;
            }
            fulfillJson(route, 200, """
                    {
                      "items": [%s],
                      "nextCursor": null,
                      "hasMore": false,
                      "limit": 25
                    }
                    """.formatted(agentDispatchAttemptJson(taskId, providerId, profileId, workItemId)));
        });
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
                  "headerName": "X-XSRF-TOKEN",
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

    private static String apiTokenJson(String tokenId, String workspaceId, String tokenType, String name, String prefix, String rawToken) {
        return """
                {
                  "id": "%s",
                  "workspaceId": %s,
                  "userId": "00000000-0000-0000-0000-000000000001",
                  "tokenType": "%s",
                  "name": "%s",
                  "tokenPrefix": "%s",
                  "token": %s,
                  "roleId": "00000000-0000-0000-0000-000000000401",
                  "scopes": ["work_item.read", "report.read"],
                  "createdAt": "2026-04-21T20:00:00Z"
                }
                """.formatted(
                tokenId,
                workspaceId == null ? "null" : "\"" + workspaceId + "\"",
                tokenType,
                name,
                prefix,
                rawToken == null ? "null" : "\"" + rawToken + "\""
        );
    }

    private static String systemAdminJson(String adminId, String userId, String email, String displayName, boolean active) {
        return """
                {
                  "id": "%s",
                  "userId": "%s",
                  "email": "%s",
                  "username": "%s",
                  "displayName": "%s",
                  "active": %s,
                  "grantedById": "00000000-0000-0000-0000-000000000001",
                  "grantedAt": "2026-04-21T20:00:00Z",
                  "createdAt": "2026-04-21T20:00:00Z",
                  "updatedAt": "2026-04-21T20:00:00Z"
                }
                """.formatted(adminId, userId, email, email.substring(0, email.indexOf('@')), displayName, active);
    }

    private static String importSettingsJson(String workspaceId) {
        return """
                {
                  "workspaceId": "%s",
                  "sampleJobsEnabled": true,
                  "deploymentSampleJobsEnabled": true,
                  "sampleJobsAvailable": true
                }
                """.formatted(workspaceId);
    }

    private static String importJobJson(String importJobId, String workspaceId, String status) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "requestedById": "00000000-0000-0000-0000-000000000001",
                  "provider": "csv",
                  "status": "%s",
                  "config": {"source": "browser"},
                  "openConflictCompletionAccepted": false,
                  "openConflictCompletionCount": 0,
                  "records": []
                }
                """.formatted(importJobId, workspaceId, status);
    }

    private static String importRecordJson(String recordId, String importJobId, String sourceType, String sourceId, String status, String conflictStatus) {
        return """
                {
                  "id": "%s",
                  "importJobId": "%s",
                  "sourceType": "%s",
                  "sourceId": "%s",
                  "targetType": "work_item",
                  "targetId": "00000000-0000-0000-0000-000000000d09",
                  "status": "%s",
                  "rawPayload": {
                    "title": "Browser imported story",
                    "type": "Story",
                    "status": "Open"
                  },
                  "conflictStatus": %s,
                  "conflictReason": %s
                }
                """.formatted(
                recordId,
                importJobId,
                sourceType,
                sourceId,
                status,
                conflictStatus == null ? "null" : "\"" + conflictStatus + "\"",
                conflictStatus == null ? "null" : "\"Browser conflict job\""
        );
    }

    private static String transformPresetJson(String presetId, String workspaceId) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "name": "Browser Import Preset",
                  "description": "Browser import preset",
                  "transformationConfig": {"title": ["trim"]},
                  "enabled": true,
                  "version": 1,
                  "createdAt": "2026-04-21T20:00:00Z",
                  "updatedAt": "2026-04-21T20:00:00Z"
                }
                """.formatted(presetId, workspaceId);
    }

    private static String mappingTemplateJson(String templateId, String workspaceId, String projectId, String presetId) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "name": "Browser CSV Mapping",
                  "provider": "csv",
                  "sourceType": "row",
                  "targetType": "work_item",
                  "projectId": "%s",
                  "workItemTypeKey": "story",
                  "statusKey": "open",
                  "transformPresetId": "%s",
                  "fieldMapping": {"title": "title"},
                  "defaults": {},
                  "transformationConfig": {},
                  "enabled": true,
                  "createdAt": "2026-04-21T20:00:00Z",
                  "updatedAt": "2026-04-21T20:00:00Z"
                }
                """.formatted(templateId, workspaceId, projectId, presetId);
    }

    private static String conflictResolutionJobJson(String conflictJobId, String workspaceId, String importJobId, String status) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "importJobId": "%s",
                  "requestedById": "00000000-0000-0000-0000-000000000001",
                  "resolution": "skip",
                  "scope": "filtered",
                  "status": "%s",
                  "sourceTypeFilter": "row",
                  "expectedCount": 1,
                  "matchedCount": 1,
                  "resolvedCount": %d,
                  "failedCount": 0,
                  "requestedAt": "2026-04-21T20:00:00Z",
                  "finishedAt": "2026-04-21T20:00:01Z",
                  "confirmation": "RESOLVE FILTERED CONFLICTS"
                }
                """.formatted(conflictJobId, workspaceId, importJobId, status, "completed".equals(status) ? 1 : 0);
    }

    private static String importJobDiffJson(String importJobId, String workspaceId) {
        return """
                {
                  "importJobId": "%s",
                  "workspaceId": "%s",
                  "recordCount": 1,
                  "versionCount": 1,
                  "diffCount": 0,
                  "records": []
                }
                """.formatted(importJobId, workspaceId);
    }

    private static String exportJobJson(String exportJobId, String workspaceId, String exportType, String status) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "requestedById": "00000000-0000-0000-0000-000000000001",
                  "exportType": "%s",
                  "status": "%s",
                  "fileAttachmentId": "00000000-0000-0000-0000-000000000d10",
                  "filename": "browser-import-export.csv",
                  "contentType": "text/csv",
                  "sizeBytes": 128,
                  "checksum": "sha256:browser",
                  "requestPayload": {},
                  "createdAt": "2026-04-21T20:00:00Z",
                  "startedAt": "2026-04-21T20:00:00Z",
                  "finishedAt": "2026-04-21T20:00:01Z"
                }
                """.formatted(exportJobId, workspaceId, exportType, status);
    }

    private static String importCompletionJson() {
        return """
                {
                  "completedJobs": 1,
                  "completedWithOpenConflicts": 0,
                  "acceptedOpenConflictCount": 0,
                  "lastOpenConflictCompletedAt": null
                }
                """;
    }

    private static void fulfillJson(Route route, int status, String body) {
        route.fulfill(new Route.FulfillOptions()
                .setStatus(status)
                .setContentType("application/json")
                .setBody(body));
    }

    private static String automationWorkerSettingsJson(String workspaceId) {
        return """
                {
                  "workspaceId": "%s",
                  "automationJobsEnabled": true,
                  "webhookDeliveriesEnabled": true,
                  "emailDeliveriesEnabled": true,
                  "importConflictResolutionEnabled": false,
                  "importReviewExportsEnabled": false,
                  "automationLimit": 5,
                  "webhookLimit": 5,
                  "emailLimit": 5,
                  "importConflictResolutionLimit": 10,
                  "importReviewExportLimit": 10,
                  "webhookMaxAttempts": 2,
                  "emailMaxAttempts": 2,
                  "webhookDryRun": false,
                  "emailDryRun": true,
                  "workerRunRetentionEnabled": true,
                  "workerRunRetentionDays": 0,
                  "workerRunExportBeforePrune": true,
                  "workerRunPruningAutomaticEnabled": false,
                  "workerRunPruningIntervalMinutes": 1440,
                  "agentDispatchAttemptRetentionEnabled": false,
                  "agentDispatchAttemptRetentionDays": 30,
                  "agentDispatchAttemptExportBeforePrune": true,
                  "agentDispatchAttemptPruningAutomaticEnabled": false,
                  "agentDispatchAttemptPruningIntervalMinutes": 1440,
                  "updatedAt": "2026-04-21T20:00:00Z"
                }
                """.formatted(workspaceId);
    }

    private static String automationWorkerRetentionJson(String workspaceId, String workerType, int pruned) {
        return """
                {
                  "workspaceId": "%s",
                  "workerType": "%s",
                  "triggerType": null,
                  "status": null,
                  "retentionEnabled": true,
                  "retentionDays": 0,
                  "exportBeforePrune": true,
                  "cutoff": "2026-04-21T20:00:00Z",
                  "runsEligible": 1,
                  "runsIncluded": 1,
                  "runsPruned": %d,
                  "exportJobId": "00000000-0000-0000-0000-000000000b05",
                  "fileAttachmentId": "00000000-0000-0000-0000-000000000b06",
                  "runs": []
                }
                """.formatted(workspaceId, workerType, pruned);
    }

    private static String webhookJson(String webhookId, String workspaceId) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "name": "Browser Automation Webhook",
                  "url": "http://localhost:6199/trasck-webhook",
                  "secretConfigured": true,
                  "eventTypes": ["manual"],
                  "enabled": true
                }
                """.formatted(webhookId, workspaceId);
    }

    private static String webhookDeliveryJson(String deliveryId, String webhookId, String status) {
        return """
                {
                  "id": "%s",
                  "webhookId": "%s",
                  "eventType": "manual",
                  "payload": {"source": "browser"},
                  "status": "%s",
                  "responseCode": 202,
                  "responseBody": "accepted",
                  "attemptCount": 1,
                  "createdAt": "2026-04-21T20:00:00Z"
                }
                """.formatted(deliveryId, webhookId, status);
    }

    private static String emailDeliveryJson(String deliveryId, String workspaceId, String status) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "automationJobId": "00000000-0000-0000-0000-000000000b04",
                  "actionId": "00000000-0000-0000-0000-000000000b03",
                  "provider": "maildev",
                  "fromEmail": "playwright@trasck.local",
                  "recipientEmail": "browser@example.test",
                  "subject": "Browser automation email",
                  "body": "Browser worker flow",
                  "status": "%s",
                  "attemptCount": 1,
                  "responseBody": "accepted",
                  "createdAt": "2026-04-21T20:00:00Z"
                }
                """.formatted(deliveryId, workspaceId, status);
    }

    private static String automationRuleJson(String ruleId, String workspaceId, String projectId) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "projectId": "%s",
                  "name": "Browser Automation Rule",
                  "triggerType": "manual",
                  "triggerConfig": {"source": "browser"},
                  "enabled": true,
                  "conditions": [],
                  "actions": [],
                  "createdAt": "2026-04-21T20:00:00Z",
                  "updatedAt": "2026-04-21T20:00:00Z"
                }
                """.formatted(ruleId, workspaceId, projectId);
    }

    private static String automationJobJson(String jobId, String ruleId, String workspaceId, String status) {
        return """
                {
                  "id": "%s",
                  "ruleId": "%s",
                  "workspaceId": "%s",
                  "sourceEntityType": "project",
                  "sourceEntityId": "00000000-0000-0000-0000-000000000501",
                  "status": "%s",
                  "payload": {"source": "browser"},
                  "attempts": 1,
                  "createdAt": "2026-04-21T20:00:00Z",
                  "logs": []
                }
                """.formatted(jobId, ruleId, workspaceId, status);
    }

    private static String agentProviderJson(String providerId, String workspaceId) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "providerKey": "browser-agent-provider",
                  "providerType": "generic_worker",
                  "displayName": "Browser Agent Provider",
                  "dispatchMode": "polling",
                  "callbackUrl": null,
                  "capabilitySchema": {},
                  "config": {
                    "callbackJwt": {
                      "algorithm": "RS256",
                      "currentKid": "browser-key",
                      "keys": [{"kid": "browser-key", "alg": "RS256"}]
                    }
                  },
                  "enabled": true
                }
                """.formatted(providerId, workspaceId);
    }

    private static String agentProfileJson(String profileId, String providerId, String workspaceId) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "providerId": "%s",
                  "displayName": "Browser Agent Profile",
                  "username": "browser-agent",
                  "status": "active",
                  "maxConcurrentTasks": 1,
                  "capabilities": {},
                  "config": {},
                  "projectIds": ["00000000-0000-0000-0000-000000000501"]
                }
                """.formatted(profileId, workspaceId, providerId);
    }

    private static String repositoryConnectionJson(String repositoryId, String workspaceId, String projectId) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "projectId": "%s",
                  "provider": "generic_git",
                  "name": "Browser Agent Repository",
                  "repositoryUrl": "https://example.test/browser-agent.git",
                  "defaultBranch": "main",
                  "providerMetadata": {},
                  "config": {},
                  "active": true
                }
                """.formatted(repositoryId, workspaceId, projectId);
    }

    private static String agentTaskJson(String taskId, String workspaceId, String workItemId, String profileId, String providerId, String status) {
        return """
                {
                  "id": "%s",
                  "workspaceId": "%s",
                  "workItemId": "%s",
                  "agentProfileId": "%s",
                  "providerId": "%s",
                  "requestedById": "00000000-0000-0000-0000-000000000001",
                  "status": "%s",
                  "dispatchMode": "polling",
                  "externalTaskId": "browser-external-task",
                  "contextSnapshot": {"source": "browser"},
                  "requestPayload": {"instructions": "Browser agent workflow"},
                  "resultPayload": {"summary": "Browser agent result"},
                  "events": [],
                  "messages": [],
                  "artifacts": [{
                    "id": "00000000-0000-0000-0000-000000000c06",
                    "agentTaskId": "%s",
                    "artifactType": "review",
                    "name": "Browser Agent Review",
                    "externalUrl": "https://example.test/agent/review",
                    "metadata": {}
                  }],
                  "repositories": [],
                  "dispatchAttempts": [%s],
                  "callbackToken": "browser-callback-token"
                }
                """.formatted(taskId, workspaceId, workItemId, profileId, providerId, status, taskId,
                agentDispatchAttemptJson(taskId, providerId, profileId, workItemId));
    }

    private static String agentDispatchAttemptJson(String taskId, String providerId, String profileId, String workItemId) {
        return """
                {
                  "id": "00000000-0000-0000-0000-000000000c08",
                  "workspaceId": "00000000-0000-0000-0000-000000000101",
                  "agentTaskId": "%s",
                  "providerId": "%s",
                  "agentProfileId": "%s",
                  "workItemId": "%s",
                  "attemptType": "dispatch",
                  "dispatchMode": "polling",
                  "providerType": "generic_worker",
                  "transport": "polling",
                  "status": "succeeded",
                  "externalTaskId": "browser-external-task",
                  "idempotencyKey": "generic_worker:%s:dispatch",
                  "externalDispatch": false,
                  "requestPayload": {"source": "browser"},
                  "responsePayload": {"status": "succeeded"},
                  "startedAt": "2026-04-21T20:00:00Z",
                  "finishedAt": "2026-04-21T20:00:01Z"
                }
                """.formatted(taskId, providerId, profileId, workItemId, taskId);
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
