package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("e2e")
@Tag("frontend")
@Tag("real-backend")
@Tag("work-item-collaboration-workflow")
class WorkItemCollaborationRealWorkflowTest extends RealFrontendWorkflowSupport {
    @Test
    void realBackendCollaborationRecordsAreCreatedThroughWorkItemDetailUi() throws Exception {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for work item collaboration real frontend workflows");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());
        RuntimeChecks.requireHttpService("Trasck frontend", config.frontendBaseUrl(), "/", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession apiSession = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                Browser browser = BrowserFactory.launch(playwright, config);
                BrowserSession browserSession = BrowserSession.start(browser, config, "work-item-collaboration-real-workflow")) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            SetupBootstrap.BootstrapContext login = SetupBootstrap.require(playwright, config);
            String suffix = UniqueData.suffix();
            String primaryTitle = "Browser collaboration UI story " + suffix;
            String peerTitle = "Browser collaboration UI peer " + suffix;
            String commentText = "Browser collaboration UI comment " + suffix;
            String editedCommentText = "Browser collaboration UI edited comment " + suffix;
            String workLogText = "Browser collaboration UI work log " + suffix;
            String labelName = "browser-ui-label-" + suffix;
            String customLinkType = "verifies_" + suffix.toLowerCase().replace("-", "_");

            JsonNode workspacePolicy = apiSession.requireJson(
                    apiSession.get("/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy"),
                    200);
            JsonNode projectPolicy = apiSession.requireJson(
                    apiSession.get("/api/v1/projects/" + workspace.projectId() + "/security-policy"),
                    200);
            cleanup.add(() -> apiSession.patch(
                    "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                    JsonSupport.object("visibility", projectPolicy.path("visibility").asText("private"))));
            cleanup.add(() -> apiSession.patch(
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy",
                    JsonSupport.object("anonymousReadEnabled", workspacePolicy.path("anonymousReadEnabled").asBoolean(false))));
            apiSession.requireJson(apiSession.patch(
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy",
                    JsonSupport.object("anonymousReadEnabled", true)
            ), 200);
            apiSession.requireJson(apiSession.patch(
                    "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                    JsonSupport.object("visibility", "public")
            ), 200);

            Page page = browserSession.page();
            installFrontendContext(page, workspace);
            loginThroughUi(page, login);

            page.navigate("/work");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Project Work"))).isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();

            JsonNode primary = createWorkItemThroughUi(page, apiSession, workspace.projectId(), primaryTitle);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primary.path("id").asText());
            JsonNode peer = createWorkItemThroughUi(page, apiSession, workspace.projectId(), peerTitle);
            cleanup.delete(apiSession, "/api/v1/work-items/" + peer.path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load").setExact(true)).click();
            page.getByText(primaryTitle).click();
            assertThat(page.locator("xpath=//h2[contains(normalize-space(), " + xpathLiteral(primaryTitle) + ")]").first()).isVisible();

            Locator comments = collaborationSection(page, "Comments");
            comments.getByLabel("Comment").fill(commentText);
            comments.getByLabel("Comment visibility").selectOption("workspace");
            comments.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Add comment")).click();
            JsonNode comment = waitForRecordByField(apiSession,
                    "/api/v1/work-items/" + primary.path("id").asText() + "/comments",
                    "bodyMarkdown",
                    commentText);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primary.path("id").asText() + "/comments/" + comment.path("id").asText());
            assertThat(comments.getByText(commentText)).isVisible();
            comments.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Edit comment")).click();
            comments.getByLabel("Edit comment").fill(editedCommentText);
            comments.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Save edit")).click();
            waitForRecordByField(apiSession,
                    "/api/v1/work-items/" + primary.path("id").asText() + "/comments",
                    "bodyMarkdown",
                    editedCommentText);
            assertThat(comments.getByText(editedCommentText)).isVisible();

            Locator links = collaborationSection(page, "Links");
            links.getByLabel("Target work item").selectOption(peer.path("id").asText());
            links.getByLabel("Link type").selectOption("custom");
            links.getByLabel("Custom link type").fill(customLinkType);
            links.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Add link")).click();
            JsonNode link = waitForRecordByField(apiSession,
                    "/api/v1/work-items/" + primary.path("id").asText() + "/links",
                    "linkType",
                    customLinkType);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primary.path("id").asText() + "/links/" + link.path("id").asText());
            assertThat(links.getByText(customLinkType)).isVisible();

            Locator watchers = collaborationSection(page, "Watchers");
            watchers.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Watch yourself")).click();
            waitForRecordByField(apiSession,
                    "/api/v1/work-items/" + primary.path("id").asText() + "/watchers",
                    "userId",
                    apiSession.userId());
            cleanup.delete(apiSession, "/api/v1/work-items/" + primary.path("id").asText() + "/watchers/" + apiSession.userId());
            assertTrue(apiSession.requireJson(
                    apiSession.get("/api/v1/work-items/" + primary.path("id").asText() + "/watchers"),
                    200).isArray());

            Locator workLogs = collaborationSection(page, "Work Logs");
            workLogs.getByLabel("Minutes").fill("45");
            workLogs.getByLabel("Work date").fill("2026-04-21");
            workLogs.getByLabel("Work log description").fill(workLogText);
            workLogs.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Log work")).click();
            JsonNode workLog = waitForRecordByField(apiSession,
                    "/api/v1/work-items/" + primary.path("id").asText() + "/work-logs",
                    "descriptionMarkdown",
                    workLogText);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primary.path("id").asText() + "/work-logs/" + workLog.path("id").asText());
            assertThat(workLogs.getByText(workLogText)).isVisible();
            workLogs.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Edit work log")).click();
            workLogs.getByLabel("Edit minutes").fill("60");
            workLogs.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Save work log")).click();
            waitForRecordByField(apiSession,
                    "/api/v1/work-items/" + primary.path("id").asText() + "/work-logs",
                    "minutesSpent",
                    "60");
            assertThat(workLogs.getByText("60 minutes").first()).isVisible();

            Locator labels = collaborationSection(page, "Labels");
            labels.getByLabel("Label name").fill(labelName);
            labels.getByLabel("Label color").fill("#3366ff");
            labels.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Create label")).click();
            JsonNode label = waitForRecordByField(apiSession,
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/labels",
                    "name",
                    labelName);
            cleanup.delete(apiSession, "/api/v1/workspaces/" + workspace.workspaceId() + "/labels/" + label.path("id").asText());
            cleanup.delete(apiSession, "/api/v1/work-items/" + primary.path("id").asText() + "/labels/" + label.path("id").asText());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Refresh detail")).click();
            labels.getByLabel("Workspace label").selectOption(label.path("id").asText());
            labels.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Add label")).click();
            waitForRecordByField(apiSession,
                    "/api/v1/work-items/" + primary.path("id").asText() + "/labels",
                    "name",
                    labelName);
            cleanup.delete(apiSession, "/api/v1/work-items/" + primary.path("id").asText() + "/labels/" + label.path("id").asText());
            assertThat(labels.getByText(labelName).first()).isVisible();

            Locator attachments = collaborationSection(page, "Attachments");
            Path uploadFile = Files.createTempFile("trasck-collaboration-" + suffix, ".txt");
            Files.writeString(uploadFile, "Browser collaboration attachment " + suffix, StandardCharsets.UTF_8);
            attachments.getByLabel("Attachment visibility").selectOption("public");
            attachments.getByLabel("Attachment file").setInputFiles(uploadFile);
            JsonNode attachment = waitForRecordByField(apiSession,
                    "/api/v1/work-items/" + primary.path("id").asText() + "/attachments",
                    "filename",
                    uploadFile.getFileName().toString());
            cleanup.delete(apiSession, "/api/v1/work-items/" + primary.path("id").asText() + "/attachments/" + attachment.path("id").asText());
            assertThat(attachments.getByText(uploadFile.getFileName().toString())).isVisible();
            assertThat(attachments.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Download")).first()).isVisible();

            Locator activity = collaborationSection(page, "Activity");
            assertThat(activity.getByText("work_item").first()).isVisible();

            page.navigate("/public/projects/" + workspace.projectId());
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Public Project Preview"))).isVisible();
            openPublicPreviewWorkItem(page, primaryTitle);
            assertThat(page.getByText(editedCommentText).first()).isVisible();
            assertThat(page.getByText(uploadFile.getFileName().toString()).first()).isVisible();
            assertFalse(page.locator("body").textContent().contains("storageKey"));

            browserSession.screenshot();
            browserSession.ignoreConsoleErrorsContaining("/api/v1/auth/me");
            browserSession.assertNoConsoleErrors();
        }
    }

    private JsonNode createWorkItemThroughUi(Page page, AuthSession session, String projectId, String title) {
        Locator workPanel = panel(page, "Project Work");
        workPanel.getByLabel("Title").fill(title);
        workPanel.getByLabel("Work item visibility").selectOption("inherited");
        workPanel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Create").setExact(true)).click();
        JsonNode workItem = waitForRecordByField(session,
                "/api/v1/projects/" + projectId + "/work-items?limit=100",
                "title",
                title);
        assertThat(page.getByText(title).first()).isVisible();
        return workItem;
    }

    private Locator collaborationSection(Page page, String title) {
        return page.locator("xpath=//section[contains(concat(' ', normalize-space(@class), ' '), ' collaboration-section ')][.//h3[normalize-space()="
                + xpathLiteral(title) + "]]").first();
    }

    private void openPublicPreviewWorkItem(Page page, String title) {
        for (int attempt = 0; attempt < 12; attempt++) {
            Locator titleLocator = page.getByText(title).first();
            if (titleLocator.count() > 0 && titleLocator.isVisible()) {
                titleLocator.click();
                return;
            }
            Locator moreButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("More work items"));
            if (moreButton.count() == 0 || moreButton.isDisabled()) {
                break;
            }
            moreButton.click();
        }
        throw new AssertionError("Could not find public preview work item: " + title);
    }
}
