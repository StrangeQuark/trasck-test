package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.BrowserSession;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("real-backend")
@Tag("program-admin-token-workflow")
class ProgramAdminTokenRealWorkflowTest extends RealFrontendWorkflowSupport {
    @Test
    void realBackendProgramAdminAndTokenWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for program/admin real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "program-admin-token-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/programs");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Program Portfolio"))).isVisible();
            String programName = "Browser Portfolio " + suffix;
            panel(page, "Program Portfolio").getByLabel("Name").fill(programName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create program")).click();
            JsonNode program = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/programs",
                    "name",
                    programName);
            String programId = program.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/programs/" + programId);
            panel(page, "Program Portfolio").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Load")).click();
            panel(page, "Program Portfolio").getByLabel("Program").selectOption(programId);
            panel(page, "Program Projects").getByLabel("Project ID").fill(workspace.projectId());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign project")).click();
            cleanup.delete(apiSession, "/api/v1/programs/" + programId + "/projects/" + workspace.projectId());
            assertThat(page.getByText(workspace.projectId()).first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load summary")).click();
            assertThat(page.getByText("program").first()).isVisible();
            page.onceDialog(dialog -> dialog.accept());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Archive")).click();
            waitForProgramStatus(apiSession, programId, "archived");

            page.navigate("/workspace-settings");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Workspace Security Policy"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).first().click();
            assertThat(page.getByText("Workspace Members").first()).isVisible();

            page.navigate("/project-settings");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Project Security Policy"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            assertThat(page.getByText("Project Security State").first()).isVisible();

            page.navigate("/tokens");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Personal Token"))).isVisible();
            String personalTokenName = "Browser Personal Token " + suffix;
            panel(page, "Personal Token").getByLabel("Name").fill(personalTokenName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create token")).click();
            JsonNode personalToken = waitForRecordByField(apiSession, "/api/v1/auth/tokens/personal", "name", personalTokenName);
            cleanup.delete(apiSession, "/api/v1/auth/tokens/" + personalToken.path("id").asText());
            assertThat(page.getByText(personalTokenName).first()).isVisible();

            JsonNode memberRole = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/roles",
                    "key",
                    "member");
            String serviceTokenName = "Browser Service Token " + suffix;
            Locator serviceTokenPanel = panel(page, "Service Token");
            serviceTokenPanel.getByLabel("Name", new Locator.GetByLabelOptions().setExact(true)).fill(serviceTokenName);
            serviceTokenPanel.getByLabel("Username", new Locator.GetByLabelOptions().setExact(true)).fill("svc-browser-" + suffix);
            serviceTokenPanel.getByLabel("Display name", new Locator.GetByLabelOptions().setExact(true)).fill("Browser Service " + suffix);
            serviceTokenPanel.getByLabel("Role ID").fill(memberRole.path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create service token")).click();
            JsonNode serviceToken = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/service-tokens",
                    "name",
                    serviceTokenName);
            cleanup.delete(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/service-tokens/" + serviceToken.path("id").asText());
            assertThat(page.getByText(serviceTokenName).first()).isVisible();

            page.navigate("/system");
            assertThat(page.locator("xpath=//h2[normalize-space()='System Admins']").first()).isVisible();

            browserSession.screenshot();
            browserSession.ignoreConsoleErrorsContaining("/api/v1/programs/");
            browserSession.assertNoConsoleErrors();
        }
    }

    private void waitForProgramStatus(AuthSession session, String programId, String status) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode program = session.requireJson(session.get("/api/v1/programs/" + programId), 200);
            if (status.equals(program.path("status").asText())) {
                assertEquals(status, program.path("status").asText());
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for program status " + status, interrupted);
            }
        }
        JsonNode program = session.requireJson(session.get("/api/v1/programs/" + programId), 200);
        assertEquals(status, program.path("status").asText());
    }
}
