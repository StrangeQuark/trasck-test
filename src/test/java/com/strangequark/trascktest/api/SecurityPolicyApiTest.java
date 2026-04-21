package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("security")
class SecurityPolicyApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void workspaceAndProjectPoliciesRequireAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);
            APIResponse workspacePolicy = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy");
            ApiDiagnostics.writeSnippet("workspace-policy-unauthenticated", "GET workspace security policy without auth", workspacePolicy);
            assertTrue(workspacePolicy.status() == 401 || workspacePolicy.status() == 403, workspacePolicy.text());

            APIResponse projectPolicy = request.get("/api/v1/projects/" + workspace.projectId() + "/security-policy");
            ApiDiagnostics.writeSnippet("project-policy-unauthenticated", "GET project security policy without auth", projectPolicy);
            assertTrue(projectPolicy.status() == 401 || projectPolicy.status() == 403, projectPolicy.text());
            request.dispose();
        }
    }

    @Test
    void authenticatedWorkspaceAndProjectPoliciesExposeEffectiveLimitsAndRejectInvalidUpdates() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for authenticated API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config)) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            APIResponse workspacePolicyResponse = session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy");
            ApiDiagnostics.writeSnippet("workspace-policy-authenticated", "GET workspace security policy with auth", workspacePolicyResponse);
            JsonNode workspacePolicy = session.requireJson(workspacePolicyResponse, 200);
            assertEquals(workspace.workspaceId(), workspacePolicy.path("workspaceId").asText());
            assertTrue(workspacePolicy.path("attachmentMaxUploadBytes").asLong() > 0, workspacePolicy.toString());
            assertFalse(workspacePolicy.path("attachmentAllowedContentTypes").asText().isBlank(), workspacePolicy.toString());

            APIResponse projectPolicyResponse = session.get("/api/v1/projects/" + workspace.projectId() + "/security-policy");
            ApiDiagnostics.writeSnippet("project-policy-authenticated", "GET project security policy with auth", projectPolicyResponse);
            JsonNode projectPolicy = session.requireJson(projectPolicyResponse, 200);
            assertEquals(workspace.projectId(), projectPolicy.path("projectId").asText());
            assertEquals(workspace.workspaceId(), projectPolicy.path("workspaceId").asText());
            assertTrue(projectPolicy.path("attachmentMaxDownloadBytes").asLong() > 0, projectPolicy.toString());

            APIResponse invalidUpdate = session.patch(
                    "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                    JsonSupport.object("attachmentMaxUploadBytes", 0)
            );
            ApiDiagnostics.writeSnippet("project-policy-invalid-update", "PATCH project security policy with invalid size", invalidUpdate);
            assertEquals(400, invalidUpdate.status(), invalidUpdate.text());

            boolean originalAnonymousRead = workspacePolicy.path("anonymousReadEnabled").asBoolean(false);
            String originalVisibility = projectPolicy.path("visibility").asText("private");
            String publicWorkItemId = null;
            String publicCommentId = null;
            String privateCommentId = null;
            String publicAttachmentId = null;
            String restrictedAttachmentId = null;
            APIRequestContext anonymous = ApiRequestFactory.backend(playwright, config);
            try {
                JsonNode privateProjectPolicy = session.requireJson(session.patch(
                        "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                        JsonSupport.object("visibility", "private")
                ), 200);
                assertEquals("private", privateProjectPolicy.path("visibility").asText(), privateProjectPolicy.toString());
                JsonNode closedWorkspacePolicy = session.requireJson(session.patch(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy",
                        JsonSupport.object("anonymousReadEnabled", false)
                ), 200);
                assertFalse(closedWorkspacePolicy.path("anonymousReadEnabled").asBoolean(), closedWorkspacePolicy.toString());

                APIResponse closedPublicProject = anonymous.get("/api/v1/public/projects/" + workspace.projectId());
                ApiDiagnostics.writeSnippet("public-project-closed", "GET public project while workspace/project public read is disabled", closedPublicProject);
                assertEquals(404, closedPublicProject.status(), closedPublicProject.text());

                JsonNode openWorkspacePolicy = session.requireJson(session.patch(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy",
                        JsonSupport.object("anonymousReadEnabled", true)
                ), 200);
                assertTrue(openWorkspacePolicy.path("anonymousReadEnabled").asBoolean(), openWorkspacePolicy.toString());
                JsonNode publicProjectPolicy = session.requireJson(session.patch(
                        "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                        JsonSupport.object("visibility", "public")
                ), 200);
                assertEquals("public", publicProjectPolicy.path("visibility").asText(), publicProjectPolicy.toString());
                assertTrue(publicProjectPolicy.path("publicReadEnabled").asBoolean(), publicProjectPolicy.toString());

                APIResponse publicProjectResponse = anonymous.get("/api/v1/public/projects/" + workspace.projectId());
                ApiDiagnostics.writeSnippet("public-project-open", "GET public project while workspace/project public read is enabled", publicProjectResponse);
                assertEquals(200, publicProjectResponse.status(), publicProjectResponse.text());
                JsonNode publicProject = JsonSupport.read(publicProjectResponse.text());
                assertEquals(workspace.projectId(), publicProject.path("id").asText(), publicProject.toString());
                assertEquals("public", publicProject.path("visibility").asText(), publicProject.toString());

                JsonNode publicWorkItem = session.requireJson(session.post(
                        "/api/v1/projects/" + workspace.projectId() + "/work-items",
                        JsonSupport.object(
                                "typeKey", "story",
                                "title", "Playwright public story " + UniqueData.suffix(),
                                "descriptionMarkdown", "Visible through anonymous public project work item reads.",
                                "visibility", "inherited"
                        )
                ), 201);
                publicWorkItemId = publicWorkItem.path("id").asText();

                APIResponse publicWorkItemsResponse = anonymous.get("/api/v1/public/projects/" + workspace.projectId() + "/work-items?limit=25");
                ApiDiagnostics.writeSnippet("public-project-work-items-open", "GET public project work items while public read is enabled", publicWorkItemsResponse);
                assertEquals(200, publicWorkItemsResponse.status(), publicWorkItemsResponse.text());
                JsonNode publicWorkItems = JsonSupport.read(publicWorkItemsResponse.text());
                assertTrue(containsId(publicWorkItems.path("items"), publicWorkItemId), publicWorkItems.toString());

                APIResponse publicWorkItemResponse = anonymous.get("/api/v1/public/projects/" + workspace.projectId() + "/work-items/" + publicWorkItemId);
                ApiDiagnostics.writeSnippet("public-project-work-item-open", "GET public project work item while public read is enabled", publicWorkItemResponse);
                assertEquals(200, publicWorkItemResponse.status(), publicWorkItemResponse.text());
                JsonNode anonymousWorkItem = JsonSupport.read(publicWorkItemResponse.text());
                assertEquals(publicWorkItemId, anonymousWorkItem.path("id").asText(), anonymousWorkItem.toString());
                assertFalse(anonymousWorkItem.has("assigneeId"), anonymousWorkItem.toString());
                assertFalse(anonymousWorkItem.has("reporterId"), anonymousWorkItem.toString());

                JsonNode publicComment = session.requireJson(session.post(
                        "/api/v1/work-items/" + publicWorkItemId + "/comments",
                        JsonSupport.object(
                                "bodyMarkdown", "Anonymous readers can see this public collaboration note.",
                                "visibility", "workspace"
                        )
                ), 201);
                publicCommentId = publicComment.path("id").asText();
                JsonNode privateComment = session.requireJson(session.post(
                        "/api/v1/work-items/" + publicWorkItemId + "/comments",
                        JsonSupport.object(
                                "bodyMarkdown", "Anonymous readers must not see this private note.",
                                "visibility", "private"
                        )
                ), 201);
                privateCommentId = privateComment.path("id").asText();
                APIResponse publicCommentsResponse = anonymous.get("/api/v1/public/projects/" + workspace.projectId() + "/work-items/" + publicWorkItemId + "/comments");
                ApiDiagnostics.writeSnippet("public-project-work-item-comments-open", "GET public project work item comments while public read is enabled", publicCommentsResponse);
                assertEquals(200, publicCommentsResponse.status(), publicCommentsResponse.text());
                JsonNode publicComments = JsonSupport.read(publicCommentsResponse.text());
                assertEquals(1, publicComments.size(), publicComments.toString());
                assertEquals("Anonymous readers can see this public collaboration note.", publicComments.path(0).path("bodyMarkdown").asText(), publicComments.toString());
                assertFalse(publicComments.path(0).has("authorId"), publicComments.toString());
                assertFalse(publicComments.path(0).has("updatedAt"), publicComments.toString());

                byte[] publicAttachmentBytes = ("public attachment " + UniqueData.suffix()).getBytes(StandardCharsets.UTF_8);
                JsonNode publicAttachment = session.requireJson(session.postMultipart(
                        "/api/v1/work-items/" + publicWorkItemId + "/attachments/files",
                        FormData.create()
                                .set("visibility", "public")
                                .set("file", new FilePayload("public-playwright-notes.txt", "text/plain", publicAttachmentBytes))
                ), 201);
                publicAttachmentId = publicAttachment.path("id").asText();
                JsonNode restrictedAttachment = session.requireJson(session.postMultipart(
                        "/api/v1/work-items/" + publicWorkItemId + "/attachments/files",
                        FormData.create()
                                .set("visibility", "restricted")
                                .set("file", new FilePayload("restricted-playwright-notes.txt", "text/plain", "private".getBytes(StandardCharsets.UTF_8)))
                ), 201);
                restrictedAttachmentId = restrictedAttachment.path("id").asText();
                APIResponse publicAttachmentsResponse = anonymous.get("/api/v1/public/projects/" + workspace.projectId() + "/work-items/" + publicWorkItemId + "/attachments");
                ApiDiagnostics.writeSnippet("public-project-work-item-attachments-open", "GET public project work item attachments while public read is enabled", publicAttachmentsResponse);
                assertEquals(200, publicAttachmentsResponse.status(), publicAttachmentsResponse.text());
                JsonNode publicAttachments = JsonSupport.read(publicAttachmentsResponse.text());
                assertEquals(1, publicAttachments.size(), publicAttachments.toString());
                assertEquals(publicAttachmentId, publicAttachments.path(0).path("id").asText(), publicAttachments.toString());
                assertFalse(publicAttachments.path(0).has("storageKey"), publicAttachments.toString());
                assertFalse(publicAttachments.path(0).has("uploaderId"), publicAttachments.toString());
                String downloadUrl = publicAttachments.path(0).path("downloadUrl").asText();
                assertTrue(downloadUrl.contains("/download?token="), publicAttachments.toString());
                APIResponse publicDownload = anonymous.get(downloadUrl);
                ApiDiagnostics.writeSnippet("public-project-work-item-attachment-download-open", "GET public project work item attachment signed download", publicDownload);
                assertEquals(200, publicDownload.status(), publicDownload.text());
                assertArrayEquals(publicAttachmentBytes, publicDownload.body());
                APIResponse badDownloadToken = anonymous.get("/api/v1/public/projects/" + workspace.projectId() + "/work-items/" + publicWorkItemId + "/attachments/" + publicAttachmentId + "/download?token=invalid");
                assertEquals(403, badDownloadToken.status(), badDownloadToken.text());

                session.requireJson(session.patch(
                        "/api/v1/work-items/" + publicWorkItemId,
                        JsonSupport.object("visibility", "private")
                ), 200);
                APIResponse privateCommentsResponse = anonymous.get("/api/v1/public/projects/" + workspace.projectId() + "/work-items/" + publicWorkItemId + "/comments");
                assertEquals(404, privateCommentsResponse.status(), privateCommentsResponse.text());
                APIResponse privateAttachmentsResponse = anonymous.get("/api/v1/public/projects/" + workspace.projectId() + "/work-items/" + publicWorkItemId + "/attachments");
                assertEquals(404, privateAttachmentsResponse.status(), privateAttachmentsResponse.text());
                APIResponse privateWorkItemResponse = anonymous.get("/api/v1/public/projects/" + workspace.projectId() + "/work-items/" + publicWorkItemId);
                ApiDiagnostics.writeSnippet("public-project-work-item-private", "GET private work item through public project read", privateWorkItemResponse);
                assertEquals(404, privateWorkItemResponse.status(), privateWorkItemResponse.text());
            } finally {
                if (publicWorkItemId != null) {
                    if (publicAttachmentId != null) {
                        session.delete("/api/v1/work-items/" + publicWorkItemId + "/attachments/" + publicAttachmentId);
                    }
                    if (restrictedAttachmentId != null) {
                        session.delete("/api/v1/work-items/" + publicWorkItemId + "/attachments/" + restrictedAttachmentId);
                    }
                    if (privateCommentId != null) {
                        session.delete("/api/v1/work-items/" + publicWorkItemId + "/comments/" + privateCommentId);
                    }
                    if (publicCommentId != null) {
                        session.delete("/api/v1/work-items/" + publicWorkItemId + "/comments/" + publicCommentId);
                    }
                    session.delete("/api/v1/work-items/" + publicWorkItemId);
                }
                session.patch(
                        "/api/v1/projects/" + workspace.projectId() + "/security-policy",
                        JsonSupport.object("visibility", originalVisibility)
                );
                session.patch(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/security-policy",
                        JsonSupport.object("anonymousReadEnabled", originalAnonymousRead)
                );
                anonymous.dispose();
            }
        }
    }

    private static boolean containsId(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) {
                return true;
            }
        }
        return false;
    }
}
