package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.BrowserFactory;
import com.strangequark.trascktest.support.BrowserSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("real-backend")
@Tag("work-item-public-preview-workflow")
class WorkItemPublicPreviewSeededWorkflowTest extends RealFrontendWorkflowSupport {
    @Test
    void realBackendSeededWorkItemDataAppearsInBrowserWorkAndPublicPreviewRoutes() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for work item preview real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "work-item-public-preview-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();
            JsonNode primary = createStory(apiSession, cleanup, workspace.projectId(), "Browser collaboration story " + suffix);
            JsonNode peer = createStory(apiSession, cleanup, workspace.projectId(), "Browser collaboration peer " + suffix);
            String primaryId = primary.path("id").asText();
            String peerId = peer.path("id").asText();

            JsonNode comment = apiSession.requireJson(apiSession.post("/api/v1/work-items/" + primaryId + "/comments", JsonSupport.object(
                    "bodyMarkdown", "Browser collaboration comment " + suffix,
                    "visibility", "public"
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
                    "visibility", "public"
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
            browserSession.ignoreConsoleErrorsContaining("/api/v1/auth/me");
            browserSession.assertNoConsoleErrors();
        }
    }
}
