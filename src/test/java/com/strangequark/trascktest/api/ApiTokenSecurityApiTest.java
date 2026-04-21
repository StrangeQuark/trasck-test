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
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("auth")
@Tag("security")
class ApiTokenSecurityApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void personalTokensHideRawValuesOnListEnforceScopesAndStopWorkingAfterRevocation() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for token security coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);

            APIResponse createResponse = session.post("/api/v1/auth/tokens/personal", JsonSupport.object(
                    "name", "Playwright personal token " + UniqueData.suffix(),
                    "scopes", List.of("report.read")
            ));
            ApiDiagnostics.writeSnippet("personal-token-create", "POST /api/v1/auth/tokens/personal", createResponse);
            JsonNode created = session.requireJson(createResponse, 201);
            String tokenId = created.path("id").asText();
            String rawToken = created.path("token").asText();
            cleanup.delete(session, "/api/v1/auth/tokens/" + tokenId);
            assertTrue(rawToken.startsWith("trpat_"), created.toString());
            assertEquals("report.read", created.path("scopes").get(0).asText());

            JsonNode listed = session.requireJson(session.get("/api/v1/auth/tokens/personal"), 200);
            JsonNode matchingListedToken = firstById(listed, tokenId);
            assertFalse(matchingListedToken.isMissingNode(), listed.toString());
            assertTrue(matchingListedToken.path("token").isMissingNode() || matchingListedToken.path("token").isNull(),
                    "Token list must not expose raw token values: " + matchingListedToken);

            APIRequestContext tokenRequest = ApiRequestFactory.backendWithBearer(playwright, config, rawToken);
            APIResponse deniedByScope = tokenRequest.get("/api/v1/projects/" + workspace.projectId() + "/work-items?limit=1");
            ApiDiagnostics.writeSnippet("personal-token-scope-denial", "GET project work items using report.read-only personal token", deniedByScope);
            assertEquals(403, deniedByScope.status(), deniedByScope.text());

            APIResponse revokeResponse = session.delete("/api/v1/auth/tokens/" + tokenId);
            ApiDiagnostics.writeSnippet("personal-token-revoke", "DELETE /api/v1/auth/tokens/{tokenId}", revokeResponse);
            assertEquals(204, revokeResponse.status(), revokeResponse.text());

            APIResponse revokedTokenRequest = tokenRequest.get("/api/v1/auth/me");
            ApiDiagnostics.writeSnippet("personal-token-revoked-me", "GET /api/v1/auth/me using revoked personal token", revokedTokenRequest);
            assertEquals(401, revokedTokenRequest.status(), revokedTokenRequest.text());
            tokenRequest.dispose();
        }
    }

    @Test
    void serviceTokensHideRawValuesOnListHonorScopesAndStopWorkingAfterRevocation() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for service-token security coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();

            APIResponse createResponse = session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/service-tokens", JsonSupport.object(
                    "name", "Playwright service token " + suffix,
                    "username", "svc-pw-" + suffix,
                    "displayName", "Playwright Service Token",
                    "scopes", List.of("work_item.read")
            ));
            ApiDiagnostics.writeSnippet("service-token-create", "POST workspace service token", createResponse);
            JsonNode created = session.requireJson(createResponse, 201);
            String tokenId = created.path("id").asText();
            String rawToken = created.path("token").asText();
            cleanup.delete(session, "/api/v1/workspaces/" + workspace.workspaceId() + "/service-tokens/" + tokenId);
            assertTrue(rawToken.startsWith("trsvc_"), created.toString());
            assertEquals("service", created.path("tokenType").asText());

            JsonNode listed = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/service-tokens"), 200);
            JsonNode matchingListedToken = firstById(listed, tokenId);
            assertFalse(matchingListedToken.isMissingNode(), listed.toString());
            assertTrue(matchingListedToken.path("token").isMissingNode() || matchingListedToken.path("token").isNull(),
                    "Service-token list must not expose raw token values: " + matchingListedToken);

            APIRequestContext tokenRequest = ApiRequestFactory.backendWithBearer(playwright, config, rawToken);
            APIResponse readAllowed = tokenRequest.get("/api/v1/projects/" + workspace.projectId() + "/work-items?limit=1");
            ApiDiagnostics.writeSnippet("service-token-work-item-read", "GET project work items using work_item.read service token", readAllowed);
            assertEquals(200, readAllowed.status(), readAllowed.text());

            APIResponse createDenied = tokenRequest.post("/api/v1/projects/" + workspace.projectId() + "/work-items", com.microsoft.playwright.options.RequestOptions.create().setData(JsonSupport.object(
                    "typeKey", "story",
                    "title", "Service token denied create " + suffix,
                    "reporterId", session.userId(),
                    "visibility", "workspace"
            )));
            ApiDiagnostics.writeSnippet("service-token-scope-create-denial", "POST work item using work_item.read service token", createDenied);
            assertEquals(403, createDenied.status(), createDenied.text());

            APIResponse revokeResponse = session.delete("/api/v1/workspaces/" + workspace.workspaceId() + "/service-tokens/" + tokenId);
            ApiDiagnostics.writeSnippet("service-token-revoke", "DELETE workspace service token", revokeResponse);
            assertEquals(204, revokeResponse.status(), revokeResponse.text());

            APIResponse revokedTokenRequest = tokenRequest.get("/api/v1/auth/me");
            ApiDiagnostics.writeSnippet("service-token-revoked-me", "GET /api/v1/auth/me using revoked service token", revokedTokenRequest);
            assertEquals(401, revokedTokenRequest.status(), revokedTokenRequest.text());
            tokenRequest.dispose();
        }
    }

    private JsonNode firstById(JsonNode nodes, String id) {
        for (JsonNode node : nodes) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
