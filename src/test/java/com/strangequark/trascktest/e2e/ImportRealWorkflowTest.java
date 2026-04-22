package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
@Tag("import-workflow")
class ImportRealWorkflowTest extends RealFrontendWorkflowSupport {
    @Test
    void realBackendImportWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for import real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "import-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();
            String importedTitle = "Browser imported story " + suffix;
            String importSource = "browser-real-" + suffix;
            cleanup.add(() -> deleteWorkItemsByTitle(apiSession, workspace.projectId(), importedTitle));

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/imports");
            assertThat(page.locator("xpath=//h2[normalize-space()='Import Job']").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();

            String presetName = "Browser Import Preset " + suffix;
            Locator presetPanel = panel(page, "Transform Preset");
            presetPanel.getByLabel("Name", new Locator.GetByLabelOptions().setExact(true)).fill(presetName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create preset")).click();
            JsonNode preset = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/import-transform-presets",
                    "name",
                    presetName);
            String presetId = preset.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/import-transform-presets/" + presetId);

            String templateName = "Browser Import Template " + suffix;
            Locator templatePanel = panel(page, "Mapping Template");
            templatePanel.getByLabel("Name", new Locator.GetByLabelOptions().setExact(true)).fill(templateName);
            templatePanel.getByLabel("Source type").selectOption("row");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create template")).click();
            JsonNode template = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/import-mapping-templates",
                    "name",
                    templateName);
            String templateId = template.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/import-mapping-templates/" + templateId);

            Locator jobPanel = panel(page, "Import Job");
            jobPanel.getByLabel("Provider").selectOption("csv");
            jobPanel.getByLabel("Source label").fill(importSource);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create job")).click();
            JsonNode importJob = waitForImportJobByConfigSource(apiSession, workspace.workspaceId(), importSource);
            String importJobId = importJob.path("id").asText();
            cleanup.add(() -> postIgnoringStatus(apiSession, "/api/v1/import-jobs/" + importJobId + "/cancel"));

            Locator parsePanel = panel(page, "Parse");
            parsePanel.getByLabel("Import job").selectOption(importJobId);
            parsePanel.getByLabel("Source type").selectOption("row");
            parsePanel.getByLabel("Content").fill("""
                    id,title,type,status,description,visibility
                    BROWSER-%s,%s,Story,Open,Created through the browser import workflow,Public
                    """.formatted(suffix, importedTitle));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Parse")).click();
            assertThat(page.getByText("recordsParsed").first()).isVisible();

            Locator materializePanel = panel(page, "Materialize");
            materializePanel.getByLabel("Import job").selectOption(importJobId);
            materializePanel.getByLabel("Template").selectOption(templateId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Materialize")).click();
            assertThat(page.getByText("recordsMaterialized").first()).isVisible();
            assertFalse(workItemsByTitle(apiSession, workspace.projectId(), importedTitle).isEmpty());

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
        }
    }
}
