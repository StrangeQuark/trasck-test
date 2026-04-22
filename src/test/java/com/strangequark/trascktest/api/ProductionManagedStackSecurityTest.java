package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.ManagedProductionBackend;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.UniqueData;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("security")
@Tag("managed-stack")
class ProductionManagedStackSecurityTest {
    private static final String PASSWORD = "correct-horse-battery-staple";

    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void productionLikeStartupRejectsUnsafeDefaults() {
        assumeManagedProductionStackEnabled();

        ManagedProductionBackend.StartupFailure failure = ManagedProductionBackend.expectStartupFailure(
                config,
                "managed-prod-unsafe-defaults",
                ManagedProductionBackend.unsafeProductionDefaultOverrides()
        );

        assertTrue(failure.logTail().contains("Unsafe production-like Trasck configuration"), failure.logTail());
        assertTrue(failure.logTail().contains("trasck.security.jwt-secret"), failure.logTail());
        assertTrue(failure.logTail().contains("trasck.security.cookie-secure"), failure.logTail());
        assertTrue(failure.logTail().contains("trasck.security.rate-limit.store"), failure.logTail());
    }

    @Test
    void productionLikeStartupRejectsUnreachableRedisRateLimitStore() {
        assumeManagedProductionStackEnabled();

        ManagedProductionBackend.StartupFailure failure = ManagedProductionBackend.expectStartupFailure(
                config,
                "managed-prod-unreachable-redis",
                Map.of("SPRING_DATA_REDIS_PORT", String.valueOf(ManagedProductionBackend.findFreePort()))
        );

        assertTrue(failure.logTail().contains("Unsafe production-like Trasck configuration"), failure.logTail());
        assertTrue(failure.logTail().contains("trasck.security.rate-limit.store=redis requires a reachable Redis connection"),
                failure.logTail());
    }

    @Test
    void managedProductionStackProtectsOpenApiAndSystemAdminGates() {
        assumeManagedProductionStackEnabled();

        try (ManagedProductionBackend backend = ManagedProductionBackend.start(
                config,
                "managed-prod-admin-gates",
                Map.of("TRASCK_SECURITY_SYSTEM_ADMIN_STEP_UP_WINDOW", "PT5S")
        );
                Playwright playwright = Playwright.create()) {
            APIRequestContext anonymous = ApiRequestFactory.baseUrl(playwright, backend.baseUrl());
            try {
                assertEquals(200, anonymous.get("/api/trasck/health").status());
                assertProtected(anonymous.get("/v3/api-docs"), "managed-prod-openapi-unauthenticated");
                assertProtected(anonymous.get("/swagger-ui.html"), "managed-prod-swagger-unauthenticated");

                JsonNode setup = createSetup(anonymous);
                String workspaceId = setup.at("/workspace/id").asText();
                String adminUserId = setup.at("/adminUser/id").asText();
                String adminEmail = setup.at("/adminUser/email").asText();
                String memberRoleId = roleId(setup, "member");
                LoginResult adminLogin = login(anonymous, adminEmail, PASSWORD);
                assertSecureAuthCookie(adminLogin.setCookie());

                APIRequestContext admin = ApiRequestFactory.baseUrlWithBearer(playwright, backend.baseUrl(), adminLogin.token());
                try {
                    assertEquals(200, admin.get("/v3/api-docs").status());
                    JsonNode admins = requireJson(admin.get("/api/v1/system-admins"), 200, "managed-prod-system-admin-list");
                    assertEquals(1, admins.size(), admins.toString());
                    assertEquals(adminUserId, admins.get(0).path("userId").asText(), admins.toString());
                    assertTrue(admins.get(0).path("active").asBoolean(), admins.toString());

                    JsonNode viewer = createWorkspaceUser(admin, workspaceId, memberRoleId, "viewer");
                    JsonNode secondViewer = createWorkspaceUser(admin, workspaceId, memberRoleId, "second-viewer");
                    LoginResult viewerLogin = login(anonymous, viewer.path("email").asText(), PASSWORD);
                    APIRequestContext viewerRequest = ApiRequestFactory.baseUrlWithBearer(playwright, backend.baseUrl(), viewerLogin.token());
                    try {
                        assertProtected(viewerRequest.get("/v3/api-docs"), "managed-prod-openapi-non-admin");
                        assertProtected(viewerRequest.get("/api/v1/system-admins"), "managed-prod-system-admin-list-non-admin");
                        assertProtected(viewerRequest.post("/api/v1/system-admins", RequestOptions.create()
                                .setData(JsonSupport.object("userId", secondViewer.path("id").asText()))), "managed-prod-system-admin-grant-non-admin");
                    } finally {
                        viewerRequest.dispose();
                    }

                    LoginResult freshAdminLogin = login(anonymous, adminEmail, PASSWORD);
                    APIRequestContext freshAdmin = ApiRequestFactory.baseUrlWithBearer(playwright, backend.baseUrl(), freshAdminLogin.token());
                    try {
                        JsonNode granted = requireJson(freshAdmin.post("/api/v1/system-admins", RequestOptions.create()
                                .setData(JsonSupport.object("userId", viewer.path("id").asText()))), 201, "managed-prod-system-admin-grant");
                        assertEquals(viewer.path("id").asText(), granted.path("userId").asText(), granted.toString());
                        assertTrue(granted.path("active").asBoolean(), granted.toString());

                        LoginResult viewerAdminLogin = login(anonymous, viewer.path("email").asText(), PASSWORD);
                        APIRequestContext viewerAdmin = ApiRequestFactory.baseUrlWithBearer(playwright, backend.baseUrl(), viewerAdminLogin.token());
                        try {
                            assertEquals(200, viewerAdmin.get("/v3/api-docs").status());
                            LoginResult revokeAdminLogin = login(anonymous, adminEmail, PASSWORD);
                            APIRequestContext revokeAdmin = ApiRequestFactory.baseUrlWithBearer(playwright, backend.baseUrl(), revokeAdminLogin.token());
                            try {
                                JsonNode revokedOriginal = requireJson(revokeAdmin.delete("/api/v1/system-admins/" + adminUserId),
                                        200, "managed-prod-system-admin-revoke-original");
                                assertFalse(revokedOriginal.path("active").asBoolean(), revokedOriginal.toString());
                                assertProtected(revokeAdmin.get("/v3/api-docs"), "managed-prod-openapi-revoked-admin");
                            } finally {
                                revokeAdmin.dispose();
                            }
                            assertEquals(409, viewerAdmin.delete("/api/v1/system-admins/" + viewer.path("id").asText()).status());

                            sleepPastStepUpWindow();
                            assertEquals(403, viewerAdmin.post("/api/v1/system-admins", RequestOptions.create()
                                    .setData(JsonSupport.object("userId", secondViewer.path("id").asText()))).status());
                        } finally {
                            viewerAdmin.dispose();
                        }
                    } finally {
                        freshAdmin.dispose();
                    }
                } finally {
                    admin.dispose();
                }
            } finally {
                anonymous.dispose();
            }
        }
    }

    private void assumeManagedProductionStackEnabled() {
        assumeTrue(config.managedProductionStackEnabled(),
                "Set TRASCK_E2E_MANAGED_PROD_STACK=true to let trasck-test launch a managed production-like backend stack");
    }

    private JsonNode createSetup(APIRequestContext request) {
        String suffix = UniqueData.suffix();
        String username = "managed-prod-" + suffix;
        APIResponse response = request.post("/api/v1/setup", RequestOptions.create()
                .setData(SetupBootstrap.setupBody(suffix, username, PASSWORD)));
        ApiDiagnostics.writeSnippet("managed-prod-setup", "POST /api/v1/setup", response);
        return requireJson(response, 201, "managed-prod-setup");
    }

    private LoginResult login(APIRequestContext request, String identifier, String password) {
        APIResponse response = request.post("/api/v1/auth/login", RequestOptions.create()
                .setData(JsonSupport.object("identifier", identifier, "password", password)));
        ApiDiagnostics.writeSnippet("managed-prod-login", "POST /api/v1/auth/login", response);
        JsonNode body = requireJson(response, 200, "managed-prod-login");
        String token = body.path("accessToken").asText();
        assertFalse(token.isBlank(), body.toString());
        return new LoginResult(token, response.headers().getOrDefault("set-cookie", ""));
    }

    private JsonNode createWorkspaceUser(APIRequestContext admin, String workspaceId, String roleId, String label) {
        String suffix = UniqueData.suffix();
        APIResponse response = admin.post("/api/v1/workspaces/" + workspaceId + "/users", RequestOptions.create()
                .setData(JsonSupport.object(
                        "email", "managed-prod-" + label + "-" + suffix + "@example.test",
                        "username", "managed-prod-" + label + "-" + suffix,
                        "displayName", "Managed Prod " + label + " " + suffix,
                        "password", PASSWORD,
                        "roleId", roleId,
                        "emailVerified", true
                )));
        ApiDiagnostics.writeSnippet("managed-prod-create-user-" + label, "POST workspace user", response);
        return requireJson(response, 201, "managed-prod-create-user-" + label);
    }

    private void assertSecureAuthCookie(String setCookie) {
        String lower = setCookie.toLowerCase();
        assertTrue(lower.contains("httponly"), setCookie);
        assertTrue(setCookie.contains("Secure"), setCookie);
    }

    private void assertProtected(APIResponse response, String snippetName) {
        ApiDiagnostics.writeSnippet(snippetName, snippetName, response);
        assertTrue(response.status() == 401 || response.status() == 403, response.text());
    }

    private JsonNode requireJson(APIResponse response, int status, String snippetName) {
        ApiDiagnostics.writeSnippet(snippetName, snippetName, response);
        assertEquals(status, response.status(), response.text());
        return JsonSupport.read(response.text());
    }

    private String roleId(JsonNode setup, String key) {
        for (JsonNode role : setup.at("/seedData/roles")) {
            if (key.equals(role.path("key").asText())) {
                return role.path("id").asText();
            }
        }
        throw new AssertionError("Role not found in setup seed data: " + key);
    }

    private void sleepPastStepUpWindow() {
        try {
            Thread.sleep(5_500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for production step-up window to expire", ex);
        }
    }

    private record LoginResult(String token, String setCookie) {
    }
}
