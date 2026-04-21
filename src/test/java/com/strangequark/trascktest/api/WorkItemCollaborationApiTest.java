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
@Tag("work-items")
class WorkItemCollaborationApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void projectWorkItemListsRequireAuthentication() {
        TestWorkspace workspace = TestWorkspace.require(config);
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);
            APIResponse response = request.get("/api/v1/projects/" + workspace.projectId() + "/work-items");
            ApiDiagnostics.writeSnippet("work-items-unauthenticated", "GET project work items without auth", response);
            assertTrue(response.status() == 401 || response.status() == 403, response.text());
            request.dispose();
        }
    }

    @Test
    void authenticatedUsersCanCreateUpdateCollaborateAndCleanUpWorkItems() {
        assumeTrue(config.hasLoginCredentials(), "Set TRASCK_E2E_LOGIN_IDENTIFIER and TRASCK_E2E_LOGIN_PASSWORD for authenticated API coverage");
        TestWorkspace workspace = TestWorkspace.require(config);
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            String suffix = UniqueData.suffix();
            JsonNode primary = createStory(session, workspace.projectId(), "Playwright collaboration story " + suffix);
            String primaryId = primary.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + primaryId);
            assertFalse(primary.path("key").asText().isBlank(), primary.toString());

            JsonNode peer = createStory(session, workspace.projectId(), "Playwright peer story " + suffix);
            String peerId = peer.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + peerId);

            APIResponse updateResponse = session.patch("/api/v1/work-items/" + primaryId, JsonSupport.object(
                    "title", "Playwright collaboration story updated " + suffix,
                    "estimatePoints", 3,
                    "estimateMinutes", 240,
                    "remainingMinutes", 120
            ));
            ApiDiagnostics.writeSnippet("work-item-update", "PATCH work item", updateResponse);
            JsonNode updated = session.requireJson(updateResponse, 200);
            assertEquals("Playwright collaboration story updated " + suffix, updated.path("title").asText());
            assertEquals(240, updated.path("estimateMinutes").asInt());

            APIResponse listResponse = session.get("/api/v1/projects/" + workspace.projectId() + "/work-items?limit=5");
            ApiDiagnostics.writeSnippet("work-items-list-authenticated", "GET project work items with auth", listResponse);
            JsonNode list = session.requireJson(listResponse, 200);
            assertTrue(list.path("items").isArray(), list.toString());

            JsonNode comment = session.requireJson(session.post("/api/v1/work-items/" + primaryId + "/comments", JsonSupport.object(
                    "bodyMarkdown", "Playwright collaboration coverage.",
                    "visibility", "workspace",
                    "bodyDocument", Map.of("type", "doc", "text", "Playwright collaboration coverage.")
            )), 201);
            String commentId = comment.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + primaryId + "/comments/" + commentId);
            assertEquals("Playwright collaboration coverage.", comment.path("bodyMarkdown").asText());
            assertEquals(1, session.requireJson(session.get("/api/v1/work-items/" + primaryId + "/comments"), 200).size());

            JsonNode link = session.requireJson(session.post("/api/v1/work-items/" + primaryId + "/links", JsonSupport.object(
                    "targetWorkItemId", peerId,
                    "linkType", "relates_to"
            )), 201);
            String linkId = link.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + primaryId + "/links/" + linkId);
            assertEquals(peerId, link.path("targetWorkItemId").asText());

            JsonNode watcher = session.requireJson(session.post("/api/v1/work-items/" + primaryId + "/watchers", JsonSupport.object(
                    "userId", session.userId()
            )), 201);
            cleanup.delete(session, "/api/v1/work-items/" + primaryId + "/watchers/" + session.userId());
            assertEquals(session.userId(), watcher.path("userId").asText());

            JsonNode workLog = session.requireJson(session.post("/api/v1/work-items/" + primaryId + "/work-logs", JsonSupport.object(
                    "userId", session.userId(),
                    "minutesSpent", 45,
                    "workDate", "2026-04-21",
                    "startedAt", "2026-04-21T14:00:00Z",
                    "descriptionMarkdown", "Playwright API collaboration coverage."
            )), 201);
            String workLogId = workLog.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + primaryId + "/work-logs/" + workLogId);
            assertEquals(45, workLog.path("minutesSpent").asInt());

            JsonNode attachment = session.requireJson(session.post("/api/v1/work-items/" + primaryId + "/attachments", JsonSupport.object(
                    "filename", "playwright-notes.txt",
                    "contentType", "text/plain",
                    "storageKey", "playwright/" + suffix + "/notes.txt",
                    "sizeBytes", 32,
                    "checksum", "sha256:" + suffix,
                    "visibility", "restricted"
            )), 201);
            String attachmentId = attachment.path("id").asText();
            cleanup.delete(session, "/api/v1/work-items/" + primaryId + "/attachments/" + attachmentId);
            assertEquals("playwright-notes.txt", attachment.path("filename").asText());
        }
    }

    private JsonNode createStory(AuthSession session, String projectId, String title) {
        APIResponse response = session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", title,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by the external Java Playwright API suite.",
                "visibility", "workspace"
        ));
        ApiDiagnostics.writeSnippet("work-item-create-" + UniqueData.suffix(), "POST project work item", response);
        return session.requireJson(response, 201);
    }
}
