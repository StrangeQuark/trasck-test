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
@Tag("notifications")
class NotificationPreferencesApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void notificationSurfacesRequireAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);

            APIResponse notifications = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/notifications");
            ApiDiagnostics.writeSnippet("notifications-unauthenticated", "GET workspace notifications without auth", notifications);
            assertTrue(notifications.status() == 401 || notifications.status() == 403, notifications.text());

            APIResponse preferences = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/notification-preferences");
            ApiDiagnostics.writeSnippet("notification-preferences-unauthenticated", "GET notification preferences without auth", preferences);
            assertTrue(preferences.status() == 401 || preferences.status() == 403, preferences.text());

            APIResponse defaults = request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/notification-defaults");
            ApiDiagnostics.writeSnippet("notification-defaults-unauthenticated", "GET notification defaults without auth", defaults);
            assertTrue(defaults.status() == 401 || defaults.status() == 403, defaults.text());
            request.dispose();
        }
    }

    @Test
    void authenticatedAdminsCanManageNotificationPreferencesAndDefaults() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for notification API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String eventType = "playwright.notification." + UniqueData.suffix();

            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/notifications"), 200).isArray());

            JsonNode preference = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/notification-preferences", JsonSupport.object(
                    "channel", "in_app",
                    "eventType", eventType,
                    "enabled", true,
                    "config", Map.of("source", "playwright")
            )), 201);
            String preferenceId = preference.path("id").asText();
            cleanup.delete(session, "/api/v1/notification-preferences/" + preferenceId);
            assertEquals(eventType, preference.path("eventType").asText(), preference.toString());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/notification-preferences"), 200).isArray());
            JsonNode updatedPreference = session.requireJson(session.patch("/api/v1/notification-preferences/" + preferenceId, JsonSupport.object(
                    "enabled", false,
                    "config", Map.of("source", "playwright", "updated", true)
            )), 200);
            assertFalse(updatedPreference.path("enabled").asBoolean(), updatedPreference.toString());

            JsonNode defaultPreference = session.requireJson(session.post("/api/v1/workspaces/" + workspace.workspaceId() + "/notification-defaults", JsonSupport.object(
                    "channel", "email",
                    "eventType", eventType,
                    "enabled", true,
                    "config", Map.of("source", "playwright-default")
            )), 201);
            String defaultPreferenceId = defaultPreference.path("id").asText();
            cleanup.delete(session, "/api/v1/notification-defaults/" + defaultPreferenceId);
            assertEquals(eventType, defaultPreference.path("eventType").asText(), defaultPreference.toString());
            assertTrue(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/notification-defaults"), 200).isArray());
            JsonNode updatedDefault = session.requireJson(session.patch("/api/v1/notification-defaults/" + defaultPreferenceId, JsonSupport.object(
                    "enabled", false,
                    "config", Map.of("source", "playwright-default", "updated", true)
            )), 200);
            assertFalse(updatedDefault.path("enabled").asBoolean(), updatedDefault.toString());
        }
    }
}
