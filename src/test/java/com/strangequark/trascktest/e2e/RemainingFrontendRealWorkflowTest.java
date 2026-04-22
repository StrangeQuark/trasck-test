package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.BrowserSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("real-backend")
@Tag("remaining-workflows")
class RemainingFrontendRealWorkflowTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void realBackendImportAutomationNotificationAndWorkerWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for remaining real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "remaining-import-automation-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();
            JsonNode sourceWorkItem = createStory(apiSession, cleanup, workspace.projectId(), "Browser automation source " + suffix);
            String importedTitle = "Browser imported story " + suffix;
            cleanup.add(() -> deleteWorkItemsByTitle(apiSession, workspace.projectId(), importedTitle));

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/imports");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Import Job"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();

            String presetName = "Browser Import Preset " + suffix;
            Locator presetPanel = panel(page, "Transform Preset");
            presetPanel.getByLabel("Name").fill(presetName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create preset")).click();
            assertThat(page.getByText(presetName).first()).isVisible();
            JsonNode preset = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/import-transform-presets",
                    "name",
                    presetName);
            String presetId = preset.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/import-transform-presets/" + presetId);

            String templateName = "Browser Import Template " + suffix;
            Locator templatePanel = panel(page, "Mapping Template");
            templatePanel.getByLabel("Name").fill(templateName);
            templatePanel.getByLabel("Source type").fill("row");
            templatePanel.getByLabel("Preset").selectOption(presetId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create template")).click();
            assertThat(page.getByText(templateName).first()).isVisible();
            JsonNode template = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/import-mapping-templates",
                    "name",
                    templateName);
            String templateId = template.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/import-mapping-templates/" + templateId);

            Locator jobPanel = panel(page, "Import Job");
            jobPanel.getByLabel("Config JSON").fill("""
                    {
                      "targetProjectId": "%s",
                      "source": "browser-real-%s"
                    }
                    """.formatted(workspace.projectId(), suffix));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create job")).click();
            JsonNode importJob = requireImportJobByConfigSource(apiSession, workspace.workspaceId(), "browser-real-" + suffix);
            String importJobId = importJob.path("id").asText();
            cleanup.add(() -> postIgnoringStatus(apiSession, "/api/v1/import-jobs/" + importJobId + "/cancel"));
            assertThat(page.getByText(importJobId).first()).isVisible();

            Locator parsePanel = panel(page, "Parse");
            parsePanel.getByLabel("Import job").selectOption(importJobId);
            parsePanel.getByLabel("Source type").fill("row");
            parsePanel.getByLabel("Content").fill("""
                    id,title,type,status,description,visibility
                    BROWSER-%s,%s,Story,Open,Created through the browser import workflow,Public
                    """.formatted(suffix, importedTitle));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Parse")).click();
            assertThat(page.getByText("recordsParsed").first()).isVisible();

            Locator materializePanel = panel(page, "Materialize");
            materializePanel.getByLabel("Import job").selectOption(importJobId);
            materializePanel.getByLabel("Template").selectOption(templateId);
            materializePanel.getByLabel("Limit").fill("5");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Materialize")).click();
            assertThat(page.getByText("created").first()).isVisible();
            assertFalse(workItemsByTitle(apiSession, workspace.projectId(), importedTitle).isEmpty());

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create export artifact")).click();
            assertThat(page.getByText("import_job_version_diffs").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Process queued")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Process review exports")).click();
            assertThat(page.getByText("Worker").first()).isVisible();

            page.navigate("/automation");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Notification Preferences"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();

            String eventType = "browser.notification." + suffix;
            Locator preferencePanel = panel(page, "Notification Preferences");
            preferencePanel.getByLabel("Event type").fill(eventType);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save preference")).click();
            JsonNode preference = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/notification-preferences",
                    "eventType",
                    eventType);
            cleanup.delete(apiSession, "/api/v1/notification-preferences/" + preference.path("id").asText());
            assertThat(page.getByText(eventType).first()).isVisible();

            String defaultEventType = "browser.default." + suffix;
            preferencePanel.getByLabel("Default event").fill(defaultEventType);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save default")).click();
            JsonNode defaultPreference = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/notification-defaults",
                    "eventType",
                    defaultEventType);
            cleanup.delete(apiSession, "/api/v1/notification-defaults/" + defaultPreference.path("id").asText());
            assertThat(page.getByText(defaultEventType).first()).isVisible();

            String webhookName = "Browser Automation Webhook " + suffix;
            Locator webhookPanel = panel(page, "Webhooks");
            webhookPanel.getByLabel("Name").fill(webhookName);
            webhookPanel.getByLabel("URL").fill("https://example.test/hooks/" + suffix);
            webhookPanel.getByLabel("Secret").fill("secret-" + suffix);
            webhookPanel.getByLabel("Secret overlap seconds").fill("60");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create webhook")).click();
            assertThat(page.getByText(webhookName).first()).isVisible();
            JsonNode webhook = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/webhooks",
                    "name",
                    webhookName);
            String webhookId = webhook.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/webhooks/" + webhookId);

            String ruleName = "Browser Automation Rule " + suffix;
            Locator rulePanel = panel(page, "Automation Rule");
            rulePanel.getByLabel("Name").fill(ruleName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create rule")).click();
            assertThat(page.getByText(ruleName).first()).isVisible();
            JsonNode rule = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/automation-rules",
                    "name",
                    ruleName);
            String ruleId = rule.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/automation-rules/" + ruleId);

            Locator conditionsPanel = panel(page, "Conditions And Actions");
            conditionsPanel.getByLabel("Rule").selectOption(ruleId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add condition")).click();
            conditionsPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Use webhook")).click();
            conditionsPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Add action")).click();

            Locator executionPanel = panel(page, "Execution");
            executionPanel.getByLabel("Rule").selectOption(ruleId);
            executionPanel.getByLabel("Work item").selectOption(sourceWorkItem.path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Queue rule")).click();
            assertThat(page.getByText("queued").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run jobs")).click();
            assertThat(page.getByText("processed").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run deliveries")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run emails")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save worker settings")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Export runs")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Prune runs")).click();
            assertThat(page.getByText("Worker Runs").first()).isVisible();

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
        }
    }

    @Test
    void realBackendConfigurationSearchDashboardProgramAndAdminWorkflowUsesBrowserUi() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for remaining real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "remaining-config-dashboard-admin-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();
            JsonNode workItem = createStory(apiSession, cleanup, workspace.projectId(), "Browser reporting story " + suffix);

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/configuration");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Custom Field"))).isVisible();
            String customFieldName = "Browser Config Field " + suffix;
            String customFieldKey = "browser_config_" + suffix;
            Locator fieldPanel = panel(page, "Custom Field");
            fieldPanel.getByLabel("Name").fill(customFieldName);
            fieldPanel.getByLabel("Key").fill(customFieldKey);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create field")).click();
            assertThat(page.getByText(customFieldName).first()).isVisible();
            JsonNode customField = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/custom-fields",
                    "key",
                    customFieldKey);
            String customFieldId = customField.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/custom-fields/" + customFieldId);

            Locator contextPanel = panel(page, "Context");
            contextPanel.getByLabel("Custom field").selectOption(customFieldId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add context")).click();
            JsonNode customFieldContext = requireRecordByField(apiSession,
                    "/api/v1/custom-fields/" + customFieldId + "/contexts",
                    "projectId",
                    workspace.projectId());
            cleanup.delete(apiSession, "/api/v1/custom-fields/" + customFieldId + "/contexts/" + customFieldContext.path("id").asText());

            Locator configPanel = panel(page, "Field Configuration");
            configPanel.getByLabel("Custom field").selectOption(customFieldId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create config")).click();
            JsonNode fieldConfiguration = requireRecordByField(apiSession,
                    "/api/v1/custom-fields/" + customFieldId + "/field-configurations",
                    "projectId",
                    workspace.projectId());
            cleanup.delete(apiSession, "/api/v1/field-configurations/" + fieldConfiguration.path("id").asText());

            String screenName = "Browser Config Screen " + suffix;
            Locator screenPanel = panel(page, "Screen");
            screenPanel.getByLabel("Name").fill(screenName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create screen")).click();
            assertThat(page.getByText(screenName).first()).isVisible();
            JsonNode screen = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/screens",
                    "name",
                    screenName);
            String screenId = screen.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/screens/" + screenId);

            Locator layoutPanel = panel(page, "Screen Layout");
            layoutPanel.getByLabel("Screen").first().selectOption(screenId);
            layoutPanel.getByLabel("Custom field").selectOption(customFieldId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add field")).click();
            JsonNode screenField = requireRecordByField(apiSession,
                    "/api/v1/screens/" + screenId + "/fields",
                    "customFieldId",
                    customFieldId);
            cleanup.delete(apiSession, "/api/v1/screens/" + screenId + "/fields/" + screenField.path("id").asText());

            layoutPanel.getByLabel("Screen").last().selectOption(screenId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign screen")).click();
            JsonNode screenAssignment = requireRecordByField(apiSession,
                    "/api/v1/screens/" + screenId + "/assignments",
                    "projectId",
                    workspace.projectId());
            cleanup.delete(apiSession, "/api/v1/screens/" + screenId + "/assignments/" + screenAssignment.path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Screen details")).click();
            assertThat(page.getByText("Assignments").first()).isVisible();

            page.navigate("/filters");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Saved Filter Builder"))).isVisible();
            String filterName = "Browser Saved Filter " + suffix;
            Locator filterPanel = panel(page, "Saved Filter Builder");
            filterPanel.getByLabel("Name").fill(filterName);
            filterPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Sync")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create filter")).click();
            assertThat(page.getByText(filterName).first()).isVisible();
            JsonNode savedFilter = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/saved-filters",
                    "name",
                    filterName);
            String savedFilterId = savedFilter.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/saved-filters/" + savedFilterId);
            panel(page, "Execute").getByLabel("Saved filter").selectOption(savedFilterId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run")).click();
            assertThat(page.getByText(workItem.path("title").asText()).first()).isVisible();

            String viewName = "Browser Saved View " + suffix;
            panel(page, "Saved View").getByLabel("Name").fill(viewName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create view")).click();
            assertThat(page.getByText(viewName).first()).isVisible();
            JsonNode savedView = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/personalization/views",
                    "name",
                    viewName);
            cleanup.delete(apiSession, "/api/v1/personalization/views/" + savedView.path("id").asText());

            page.navigate("/dashboards");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Dashboard Builder"))).isVisible();
            String dashboardName = "Browser Dashboard " + suffix;
            panel(page, "Dashboard Builder").getByLabel("Name").fill(dashboardName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create dashboard")).click();
            assertThat(page.getByText(dashboardName).first()).isVisible();
            JsonNode dashboard = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/dashboards",
                    "name",
                    dashboardName);
            String dashboardId = dashboard.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/dashboards/" + dashboardId);
            panel(page, "Widget").getByLabel("Dashboard").selectOption(dashboardId);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add widget")).click();
            assertThat(page.getByText("widgets").first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load import reports")).click();
            assertThat(page.getByText("completedJobs").first()).isVisible();

            page.navigate("/programs");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Program Portfolio"))).isVisible();
            String programName = "Browser Portfolio " + suffix;
            panel(page, "Program Portfolio").getByLabel("Name").fill(programName);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create program")).click();
            assertThat(page.getByText(programName).first()).isVisible();
            JsonNode program = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/programs",
                    "name",
                    programName);
            String programId = program.path("id").asText();
            cleanup.delete(apiSession, "/api/v1/programs/" + programId);
            panel(page, "Program Projects").getByLabel("Project ID").fill(workspace.projectId());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Assign project")).click();
            cleanup.delete(apiSession, "/api/v1/programs/" + programId + "/projects/" + workspace.projectId());
            assertThat(page.getByText(workspace.projectId()).first()).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load summary")).click();
            assertThat(page.getByText("program").first()).isVisible();
            page.onceDialog(dialog -> dialog.accept());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Archive")).click();
            assertEquals("archived", apiSession.requireJson(apiSession.get("/api/v1/programs/" + programId), 200).path("status").asText());

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
            JsonNode personalToken = requireRecordByField(apiSession, "/api/v1/auth/tokens/personal", "name", personalTokenName);
            cleanup.delete(apiSession, "/api/v1/auth/tokens/" + personalToken.path("id").asText());
            assertThat(page.getByText(personalTokenName).first()).isVisible();

            JsonNode memberRole = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/roles",
                    "key",
                    "member");
            String serviceTokenName = "Browser Service Token " + suffix;
            Locator serviceTokenPanel = panel(page, "Service Token");
            serviceTokenPanel.getByLabel("Name").fill(serviceTokenName);
            serviceTokenPanel.getByLabel("Username").fill("svc-browser-" + suffix);
            serviceTokenPanel.getByLabel("Display name").fill("Browser Service " + suffix);
            serviceTokenPanel.getByLabel("Role ID").fill(memberRole.path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create service token")).click();
            JsonNode serviceToken = requireRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/service-tokens",
                    "name",
                    serviceTokenName);
            cleanup.delete(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/service-tokens/" + serviceToken.path("id").asText());
            assertThat(page.getByText(serviceTokenName).first()).isVisible();

            page.navigate("/system");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("System Admins"))).isVisible();

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
        }
    }

    @Test
    void realBackendWorkItemCollaborationSeedAppearsInBrowserWorkAndPublicPreviewRoutes() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for remaining real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "remaining-collaboration-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();
            JsonNode primary = createStory(apiSession, cleanup, workspace.projectId(), "Browser collaboration story " + suffix);
            JsonNode peer = createStory(apiSession, cleanup, workspace.projectId(), "Browser collaboration peer " + suffix);
            String primaryId = primary.path("id").asText();
            String peerId = peer.path("id").asText();

            JsonNode comment = apiSession.requireJson(apiSession.post("/api/v1/work-items/" + primaryId + "/comments", JsonSupport.object(
                    "bodyMarkdown", "Browser collaboration comment " + suffix,
                    "visibility", "workspace"
            )), 201);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primaryId + "/comments/" + comment.path("id").asText());
            JsonNode link = apiSession.requireJson(apiSession.post("/api/v1/work-items/" + primaryId + "/links", JsonSupport.object(
                    "targetWorkItemId", peerId,
                    "linkType", "relates_to"
            )), 201);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primaryId + "/links/" + link.path("id").asText());
            apiSession.requireJson(apiSession.post("/api/v1/work-items/" + primaryId + "/watchers", JsonSupport.object(
                    "userId", apiSession.userId()
            )), 201);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primaryId + "/watchers/" + apiSession.userId());
            JsonNode workLog = apiSession.requireJson(apiSession.post("/api/v1/work-items/" + primaryId + "/work-logs", JsonSupport.object(
                    "userId", apiSession.userId(),
                    "minutesSpent", 30,
                    "workDate", "2026-04-21",
                    "startedAt", "2026-04-21T14:00:00Z",
                    "descriptionMarkdown", "Browser collaboration work log " + suffix
            )), 201);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primaryId + "/work-logs/" + workLog.path("id").asText());
            JsonNode label = apiSession.requireJson(apiSession.post("/api/v1/workspaces/" + workspace.workspaceId() + "/labels", JsonSupport.object(
                    "name", "browser-label-" + suffix,
                    "color", "#3366ff"
            )), 201);
            cleanup.delete(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/labels/" + label.path("id").asText());
            apiSession.requireJson(apiSession.post("/api/v1/work-items/" + primaryId + "/labels", JsonSupport.object(
                    "labelId", label.path("id").asText()
            )), 201);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primaryId + "/labels/" + label.path("id").asText());
            JsonNode attachment = apiSession.requireJson(apiSession.post("/api/v1/work-items/" + primaryId + "/attachments", JsonSupport.object(
                    "filename", "browser-notes-" + suffix + ".txt",
                    "contentType", "text/plain",
                    "storageKey", "browser/" + suffix + "/notes.txt",
                    "sizeBytes", 32,
                    "checksum", "sha256:" + suffix,
                    "visibility", "workspace"
            )), 201);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primaryId + "/attachments/" + attachment.path("id").asText());

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/work");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Project Work"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            assertThat(page.getByText(primary.path("title").asText()).first()).isVisible();
            page.getByText(primary.path("title").asText()).click();
            assertThat(page.getByText(primaryId).first()).isVisible();
            assertTrue(apiSession.requireJson(apiSession.get("/api/v1/work-items/" + primaryId + "/comments"), 200).isArray());
            assertTrue(apiSession.requireJson(apiSession.get("/api/v1/work-items/" + primaryId + "/links"), 200).isArray());
            assertTrue(apiSession.requireJson(apiSession.get("/api/v1/work-items/" + primaryId + "/watchers"), 200).isArray());
            assertTrue(apiSession.requireJson(apiSession.get("/api/v1/work-items/" + primaryId + "/work-logs"), 200).isArray());
            assertTrue(apiSession.requireJson(apiSession.get("/api/v1/work-items/" + primaryId + "/attachments"), 200).isArray());
            assertTrue(apiSession.requireJson(apiSession.get("/api/v1/work-items/" + primaryId + "/activity?limit=5"), 200).path("items").isArray());

            page.navigate("/public/projects/" + workspace.projectId());
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Public Project Preview"))).isVisible();
            assertFalse(page.locator("body").textContent().contains("storageKey"));

            browserSession.screenshot();
            browserSession.assertNoConsoleErrors();
        }
    }

    private JsonNode createStory(AuthSession session, ApiCleanup cleanup, String projectId, String title) {
        APIResponse response = session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", title,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by the remaining real-backend Java Playwright browser workflow.",
                "visibility", "inherited"
        ));
        ApiDiagnostics.writeSnippet("remaining-real-work-item-create", "POST project work item for remaining browser workflow", response);
        JsonNode workItem = session.requireJson(response, 201);
        cleanup.delete(session, "/api/v1/work-items/" + workItem.path("id").asText());
        return workItem;
    }

    private void loginThroughUi(Page page, SetupBootstrap.BootstrapContext login) {
        page.navigate("/auth");
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign In"))).isVisible();
        page.getByLabel("Identifier").fill(login.loginIdentifier());
        page.getByLabel("Password").fill(login.loginPassword());
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
        assertThat(page.getByText("human").first()).isVisible();
        assertNull(page.evaluate("() => localStorage.getItem('trasck.accessToken')"));
    }

    private void installFrontendContext(Page page, TestWorkspace workspace) {
        String script = "localStorage.setItem('trasck.apiBaseUrl', '" + jsString(config.backendBaseUrl().toString()) + "');"
                + "localStorage.setItem('trasck.workspaceId', '" + jsString(workspace.workspaceId()) + "');"
                + "localStorage.setItem('trasck.projectId', '" + jsString(workspace.projectId()) + "');";
        page.addInitScript(script);
    }

    private Locator panel(Page page, String title) {
        return page.locator("xpath=//section[contains(concat(' ', normalize-space(@class), ' '), ' panel ')][.//h2[normalize-space()="
                + xpathLiteral(title) + "]]").first();
    }

    private JsonNode requireRecordByField(AuthSession session, String path, String field, String value) {
        JsonNode rows = session.requireJson(session.get(path), 200);
        for (JsonNode row : rowItems(rows)) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("Could not find record with " + field + "=" + value + " from " + path + ": " + rows);
    }

    private JsonNode requireImportJobByConfigSource(AuthSession session, String workspaceId, String source) {
        JsonNode rows = session.requireJson(session.get("/api/v1/workspaces/" + workspaceId + "/import-jobs"), 200);
        for (JsonNode row : rowItems(rows)) {
            if (source.equals(row.path("config").path("source").asText())) {
                return row;
            }
        }
        throw new AssertionError("Could not find import job with config.source=" + source + ": " + rows);
    }

    private List<JsonNode> workItemsByTitle(AuthSession session, String projectId, String title) {
        JsonNode page = session.requireJson(session.get("/api/v1/projects/" + projectId + "/work-items?limit=100"), 200);
        java.util.ArrayList<JsonNode> matches = new java.util.ArrayList<>();
        for (JsonNode item : rowItems(page)) {
            if (title.equals(item.path("title").asText())) {
                matches.add(item);
            }
        }
        return matches;
    }

    private void deleteWorkItemsByTitle(AuthSession session, String projectId, String title) {
        for (JsonNode item : workItemsByTitle(session, projectId, title)) {
            int status = session.delete("/api/v1/work-items/" + item.path("id").asText()).status();
            if (status != 200 && status != 204 && status != 404) {
                throw new AssertionError("Cleanup DELETE work item returned HTTP " + status);
            }
        }
    }

    private void postIgnoringStatus(AuthSession session, String path) {
        session.post(path, Map.of());
    }

    private Iterable<JsonNode> rowItems(JsonNode rows) {
        JsonNode items = rows.path("items");
        if (items.isArray()) {
            return items;
        }
        if (rows.isArray()) {
            return rows;
        }
        throw new AssertionError("Expected array or cursor page with items: " + rows);
    }

    private String jsString(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String xpathLiteral(String value) {
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\"", "") + "\"";
    }
}
