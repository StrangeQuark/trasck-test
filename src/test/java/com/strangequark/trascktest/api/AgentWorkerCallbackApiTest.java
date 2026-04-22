package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
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
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("agent")
@Tag("async")
class AgentWorkerCallbackApiTest {
    private static final String WORKER_TOKEN_HEADER = "X-Trasck-Worker-Token";
    private static final String CALLBACK_HEADER = "X-Trasck-Agent-Callback-Jwt";

    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void agentAdminRoutesRequireAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);

            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/agent-providers"),
                    "agent-providers-unauthenticated");
            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/agents"),
                    "agent-profiles-unauthenticated");
            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/agent-dispatch-attempts"),
                    "agent-dispatch-attempts-unauthenticated");
            assertUnauthorized(request.post("/api/v1/work-items/00000000-0000-0000-0000-000000000001/assign-agent",
                    RequestOptions.create().setData(JsonSupport.object("agentProfileId", "00000000-0000-0000-0000-000000000002"))),
                    "assign-agent-unauthenticated");
            request.dispose();
        }
    }

    @Test
    void codexAndClaudeProvidersExposeManualCliRuntimeCredentialAndProfileSetup() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for Codex/Claude provider readiness coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();

            JsonNode codexProvider = createCliProvider(session, cleanup, workspace.workspaceId(), "codex", "codex-local", "pw-codex-" + suffix, "Playwright Codex " + suffix);
            String codexProviderId = codexProvider.path("id").asText();
            JsonNode codexPreview = session.requireJson(session.post(
                    "/api/v1/agent-providers/" + codexProviderId + "/runtime-preview",
                    JsonSupport.object("action", "dispatched")
            ), 200);
            assertTrue(codexPreview.path("valid").asBoolean(), codexPreview.toString());
            assertEquals("cli_worker", codexPreview.path("runtimeMode").asText(), codexPreview.toString());
            assertEquals("backend_cli_worker", codexPreview.path("transport").asText(), codexPreview.toString());
            JsonNode codexCredential = session.requireJson(session.post(
                    "/api/v1/agent-providers/" + codexProviderId + "/credentials",
                    JsonSupport.object(
                            "credentialType", "codex_api_key",
                            "secret", "sk-codex-playwright-secret-" + suffix,
                            "metadata", JsonSupport.object("authScheme", "bearer", "environmentVariable", "CODEX_API_KEY")
                    )
            ), 201);
            assertEquals("codex_api_key", codexCredential.path("credentialType").asText(), codexCredential.toString());
            assertFalse(codexCredential.toString().contains("sk-codex-playwright-secret-" + suffix), codexCredential.toString());
            createAgentProfile(session, cleanup, workspace.workspaceId(), workspace.projectId(), codexProviderId, "Playwright Codex Agent " + suffix, "pw-codex-agent-" + suffix);

            JsonNode claudeProvider = createCliProvider(session, cleanup, workspace.workspaceId(), "claude_code", "claude-code-local", "pw-claude-" + suffix, "Playwright Claude Code " + suffix);
            String claudeProviderId = claudeProvider.path("id").asText();
            JsonNode claudePreview = session.requireJson(session.post(
                    "/api/v1/agent-providers/" + claudeProviderId + "/runtime-preview",
                    JsonSupport.object("action", "dispatched")
            ), 200);
            assertTrue(claudePreview.path("valid").asBoolean(), claudePreview.toString());
            assertEquals("cli_worker", claudePreview.path("runtimeMode").asText(), claudePreview.toString());
            assertEquals("backend_cli_worker", claudePreview.path("transport").asText(), claudePreview.toString());
            JsonNode claudeCredential = session.requireJson(session.post(
                    "/api/v1/agent-providers/" + claudeProviderId + "/credentials",
                    JsonSupport.object(
                            "credentialType", "anthropic_api_key",
                            "secret", "sk-ant-playwright-secret-" + suffix,
                            "metadata", JsonSupport.object("authScheme", "api_key", "environmentVariable", "ANTHROPIC_API_KEY")
                    )
            ), 201);
            assertEquals("anthropic_api_key", claudeCredential.path("credentialType").asText(), claudeCredential.toString());
            assertFalse(claudeCredential.toString().contains("sk-ant-playwright-secret-" + suffix), claudeCredential.toString());
            createAgentProfile(session, cleanup, workspace.workspaceId(), workspace.projectId(), claudeProviderId, "Playwright Claude Agent " + suffix, "pw-claude-agent-" + suffix);
        }
    }

    @Test
    void authenticatedAdminsCanExerciseAgentWorkerCallbackAndDispatchAttemptLifecycle() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for agent worker/callback API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();
            String providerKey = "pw-worker-" + suffix;
            String workerId = "worker-" + suffix;
            String workerSecret = "worker-secret-" + suffix;

            JsonNode provider = session.requireJson(session.post(
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-providers",
                    JsonSupport.object(
                            "providerKey", providerKey,
                            "providerType", "generic_worker",
                            "displayName", "Playwright Generic Worker " + suffix,
                            "dispatchMode", "polling",
                            "enabled", true,
                            "config", JsonSupport.object("workerWebhook", JsonSupport.object("maxAttempts", 1, "deadLetterOnExhaustion", true))
                    )
            ), 201);
            String providerId = provider.path("id").asText();
            assertEquals(providerKey, provider.path("providerKey").asText(), provider.toString());
            assertTrue(provider.path("config").path("callbackJwt").path("keys").isArray(), provider.toString());
            assertFalse(provider.toString().contains("BEGIN PRIVATE KEY"), provider.toString());
            assertTrue(containsId(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/agent-providers"), 200), providerId));

            JsonNode updatedProvider = session.requireJson(session.patch(
                    "/api/v1/agent-providers/" + providerId,
                    JsonSupport.object("displayName", "Playwright Generic Worker Updated " + suffix, "dispatchMode", "polling", "enabled", true)
            ), 200);
            assertEquals("Playwright Generic Worker Updated " + suffix, updatedProvider.path("displayName").asText(), updatedProvider.toString());

            JsonNode runtimePreview = session.requireJson(session.post(
                    "/api/v1/agent-providers/" + providerId + "/runtime-preview",
                    JsonSupport.object("action", "dispatched")
            ), 200);
            assertTrue(runtimePreview.path("valid").asBoolean(), runtimePreview.toString());
            assertFalse(runtimePreview.toString().contains("BEGIN PRIVATE KEY"), runtimePreview.toString());

            JsonNode workerCredential = session.requireJson(session.post(
                    "/api/v1/agent-providers/" + providerId + "/credentials",
                    JsonSupport.object(
                            "credentialType", "worker_token",
                            "secret", workerSecret,
                            "metadata", JsonSupport.object("workerId", workerId, "purpose", "playwright")
                    )
            ), 201);
            assertEquals("worker_token", workerCredential.path("credentialType").asText(), workerCredential.toString());
            assertFalse(workerCredential.toString().contains(workerSecret), workerCredential.toString());
            JsonNode temporaryCredential = session.requireJson(session.post(
                    "/api/v1/agent-providers/" + providerId + "/credentials",
                    JsonSupport.object("credentialType", "temporary_api_token", "secret", "temporary-secret-" + suffix)
            ), 201);
            assertTrue(session.requireJson(session.get("/api/v1/agent-providers/" + providerId + "/credentials"), 200).size() >= 3);
            assertTrue(session.requireJson(session.post("/api/v1/agent-providers/" + providerId + "/credentials/reencrypt", Map.of()), 200).size() >= 3);
            assertFalse(session.requireJson(session.post(
                    "/api/v1/agent-providers/" + providerId + "/credentials/" + temporaryCredential.path("id").asText() + "/deactivate",
                    Map.of()
            ), 200).path("active").asBoolean());
            JsonNode rotatedProvider = session.requireJson(session.post("/api/v1/agent-providers/" + providerId + "/callback-keys/rotate", Map.of()), 200);
            assertTrue(rotatedProvider.path("config").path("callbackJwt").path("keys").isArray(), rotatedProvider.toString());
            assertFalse(rotatedProvider.toString().contains("BEGIN PRIVATE KEY"), rotatedProvider.toString());

            JsonNode profile = session.requireJson(session.post(
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/agents",
                    JsonSupport.object(
                            "providerId", providerId,
                            "displayName", "Playwright Worker Agent " + suffix,
                            "username", "pw-agent-" + suffix,
                            "projectIds", List.of(workspace.projectId()),
                            "maxConcurrentTasks", 1
                    )
            ), 201);
            String profileId = profile.path("id").asText();
            assertEquals(providerId, profile.path("providerId").asText(), profile.toString());
            assertTrue(containsId(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/agents"), 200), profileId));
            JsonNode updatedProfile = session.requireJson(session.patch(
                    "/api/v1/agents/" + profileId,
                    JsonSupport.object(
                            "displayName", "Playwright Worker Agent Updated " + suffix,
                            "status", "active",
                            "maxConcurrentTasks", 1,
                            "projectIds", List.of(workspace.projectId())
                    )
            ), 200);
            assertEquals("Playwright Worker Agent Updated " + suffix, updatedProfile.path("displayName").asText(), updatedProfile.toString());

            JsonNode workItem = createStory(session, cleanup, workspace.projectId(), "Playwright agent worker story " + suffix);
            String workItemId = workItem.path("id").asText();
            JsonNode task = session.requireJson(session.post(
                    "/api/v1/work-items/" + workItemId + "/assign-agent",
                    JsonSupport.object(
                            "agentProfileId", profileId,
                            "requestPayload", JsonSupport.object("instructions", "Exercise the provider-neutral worker lifecycle.")
                    )
            ), 201);
            String taskId = task.path("id").asText();
            assertEquals("running", task.path("status").asText(), task.toString());
            assertTrue(task.path("callbackToken").isTextual(), task.toString());
            assertEquals("dispatch", task.path("dispatchAttempts").path(0).path("attemptType").asText(), task.toString());
            assertEquals(taskId, session.requireJson(session.get("/api/v1/agent-tasks/" + taskId), 200).path("id").asText());

            JsonNode manualDispatch = session.requireJson(session.post("/api/v1/agent-tasks/" + taskId + "/worker-dispatch", Map.of()), 200);
            assertEquals("trasck.worker.v1", manualDispatch.path("protocolVersion").asText(), manualDispatch.toString());
            assertEquals("webhook_push", manualDispatch.path("transport").asText(), manualDispatch.toString());

            JsonNode canceledByAdmin = session.requireJson(session.post("/api/v1/agent-tasks/" + taskId + "/cancel", Map.of()), 200);
            assertEquals("canceled", canceledByAdmin.path("status").asText(), canceledByAdmin.toString());
            JsonNode retriedByAdmin = session.requireJson(session.post("/api/v1/agent-tasks/" + taskId + "/retry", Map.of()), 200);
            assertEquals("running", retriedByAdmin.path("status").asText(), retriedByAdmin.toString());

            APIRequestContext badWorker = workerRequest(playwright, "bad-" + workerSecret);
            try {
                assertUnauthorized(badWorker.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-workers/" + providerKey + "/tasks/claim",
                        RequestOptions.create().setData(JsonSupport.object("workerId", workerId))
                ), "agent-worker-claim-bad-token");
            } finally {
                badWorker.dispose();
            }
            APIRequestContext worker = workerRequest(playwright, workerSecret);
            try {
                JsonNode claimed = requireJson(worker.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-workers/" + providerKey + "/tasks/claim",
                        RequestOptions.create().setData(JsonSupport.object("workerId", workerId, "metadata", JsonSupport.object("suite", "playwright")))
                ), 200);
                assertEquals(taskId, claimed.path("taskId").asText(), claimed.toString());
                assertEquals("polling", claimed.path("transport").asText(), claimed.toString());

                JsonNode heartbeat = requireJson(worker.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-workers/" + providerKey + "/tasks/" + taskId + "/heartbeat",
                        RequestOptions.create().setData(JsonSupport.object("workerId", workerId, "status", "waiting_for_input", "message", "Need clarification."))
                ), 200);
                assertEquals("waiting_for_input", heartbeat.path("status").asText(), heartbeat.toString());

                JsonNode humanMessage = session.requireJson(session.post(
                        "/api/v1/agent-tasks/" + taskId + "/messages",
                        JsonSupport.object("bodyMarkdown", "Use the default implementation path.")
                ), 200);
                assertEquals("running", humanMessage.path("status").asText(), humanMessage.toString());
                JsonNode changesRequested = session.requireJson(session.post(
                        "/api/v1/agent-tasks/" + taskId + "/request-changes",
                        JsonSupport.object("message", "Please add tests before review.")
                ), 200);
                assertEquals("waiting_for_input", changesRequested.path("status").asText(), changesRequested.toString());

                requireJson(worker.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-workers/" + providerKey + "/tasks/" + taskId + "/logs",
                        RequestOptions.create().setData(JsonSupport.object("workerId", workerId, "eventType", "worker_progress", "message", "Tests are running."))
                ), 200);
                requireJson(worker.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-workers/" + providerKey + "/tasks/" + taskId + "/messages",
                        RequestOptions.create().setData(JsonSupport.object("senderType", "agent", "bodyMarkdown", "I added the tests."))
                ), 200);
                JsonNode artifactTask = requireJson(worker.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-workers/" + providerKey + "/tasks/" + taskId + "/artifacts",
                        RequestOptions.create().setData(JsonSupport.object("artifactType", "branch", "name", "worker/implementation-" + suffix, "externalUrl", "https://example.test/worker/" + suffix))
                ), 200);
                assertTrue(artifactTask.path("artifacts").toString().contains("worker/implementation-" + suffix), artifactTask.toString());

                JsonNode workerCanceled = requireJson(worker.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-workers/" + providerKey + "/tasks/" + taskId + "/cancel",
                        RequestOptions.create().setData(JsonSupport.object("workerId", workerId, "message", "Cancel acknowledged."))
                ), 200);
                assertEquals("canceled", workerCanceled.path("status").asText(), workerCanceled.toString());
                JsonNode workerRetried = requireJson(worker.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-workers/" + providerKey + "/tasks/" + taskId + "/retry",
                        RequestOptions.create().setData(JsonSupport.object("workerId", workerId, "message", "Retry started."))
                ), 200);
                assertEquals("running", workerRetried.path("status").asText(), workerRetried.toString());
                assertTrue(workerRetried.path("callbackToken").isTextual(), workerRetried.toString());

                APIRequestContext badCallback = callbackRequest(playwright, "bad-callback-token");
                try {
                    assertUnauthorized(badCallback.post(
                            "/api/v1/agent-callbacks/" + providerKey,
                            RequestOptions.create().setData(JsonSupport.object("status", "completed", "message", "bad"))
                    ), "agent-callback-bad-token");
                } finally {
                    badCallback.dispose();
                }
                APIRequestContext callback = callbackRequest(playwright, workerRetried.path("callbackToken").asText());
                try {
                    JsonNode reviewed = requireJson(callback.post(
                            "/api/v1/agent-callbacks/" + providerKey,
                            RequestOptions.create().setData(JsonSupport.object(
                                    "status", "completed",
                                    "message", "Agent finished and requests review.",
                                    "resultPayload", JsonSupport.object("summary", "Implemented in the generic worker test."),
                                    "messages", List.of(JsonSupport.object("senderType", "agent", "bodyMarkdown", "Ready for review.")),
                                    "artifacts", List.of(JsonSupport.object("artifactType", "pull_request", "name", "Simulated pull request", "externalUrl", "https://example.test/pr/" + suffix))
                            ))
                    ), 200);
                    assertEquals("review_requested", reviewed.path("status").asText(), reviewed.toString());
                    assertTrue(reviewed.path("artifacts").toString().contains("Agent Review Request"), reviewed.toString());
                } finally {
                    callback.dispose();
                }
            } finally {
                worker.dispose();
            }

            JsonNode accepted = session.requireJson(session.post("/api/v1/agent-tasks/" + taskId + "/accept-result", Map.of()), 200);
            assertEquals("completed", accepted.path("status").asText(), accepted.toString());

            JsonNode dispatchAttempts = session.requireJson(session.get(
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-dispatch-attempts?agentTaskId=" + taskId + "&limit=10"
            ), 200);
            assertFalse(dispatchAttempts.path("items").isEmpty(), dispatchAttempts.toString());
            JsonNode dispatchExport = session.requireJson(session.post(
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-dispatch-attempts/export",
                    JsonSupport.object("agentTaskId", taskId, "limit", 10)
            ), 201);
            assertEquals("agent_dispatch_attempts", dispatchExport.path("exportType").asText(), dispatchExport.toString());
            JsonNode dispatchPrune = session.requireJson(session.post(
                    "/api/v1/workspaces/" + workspace.workspaceId() + "/agent-dispatch-attempts/prune",
                    JsonSupport.object("agentTaskId", taskId, "retentionDays", 1, "exportBeforePrune", false)
            ), 200);
            assertEquals(0, dispatchPrune.path("attemptsPruned").asInt(), dispatchPrune.toString());

            JsonNode deactivatedProfile = session.requireJson(session.post("/api/v1/agents/" + profileId + "/deactivate", Map.of()), 200);
            assertEquals("disabled", deactivatedProfile.path("status").asText(), deactivatedProfile.toString());
            JsonNode deactivatedProvider = session.requireJson(session.post("/api/v1/agent-providers/" + providerId + "/deactivate", Map.of()), 200);
            assertFalse(deactivatedProvider.path("enabled").asBoolean(), deactivatedProvider.toString());
        }
    }

    private JsonNode createCliProvider(
            AuthSession session,
            ApiCleanup cleanup,
            String workspaceId,
            String providerType,
            String commandProfile,
            String providerKey,
            String displayName
    ) {
        JsonNode provider = session.requireJson(session.post(
                "/api/v1/workspaces/" + workspaceId + "/agent-providers",
                JsonSupport.object(
                        "providerKey", providerKey,
                        "providerType", providerType,
                        "displayName", displayName,
                        "dispatchMode", "managed",
                        "enabled", true,
                        "config", JsonSupport.object(
                                "runtime", JsonSupport.object(
                                        "mode", "cli_worker",
                                        "externalExecutionEnabled", true,
                                        "cliWorker", JsonSupport.object("commandProfile", commandProfile)
                                )
                        )
                )
        ), 201);
        cleanup.add(() -> session.post("/api/v1/agent-providers/" + provider.path("id").asText() + "/deactivate", Map.of()));
        assertEquals(providerType, provider.path("providerType").asText(), provider.toString());
        assertFalse(provider.toString().contains("BEGIN PRIVATE KEY"), provider.toString());
        return provider;
    }

    private JsonNode createAgentProfile(
            AuthSession session,
            ApiCleanup cleanup,
            String workspaceId,
            String projectId,
            String providerId,
            String displayName,
            String username
    ) {
        JsonNode profile = session.requireJson(session.post(
                "/api/v1/workspaces/" + workspaceId + "/agents",
                JsonSupport.object(
                        "providerId", providerId,
                        "displayName", displayName,
                        "username", username,
                        "projectIds", List.of(projectId),
                        "maxConcurrentTasks", 1
                )
        ), 201);
        cleanup.add(() -> session.post("/api/v1/agents/" + profile.path("id").asText() + "/deactivate", Map.of()));
        assertEquals(providerId, profile.path("providerId").asText(), profile.toString());
        return profile;
    }

    private JsonNode createStory(AuthSession session, ApiCleanup cleanup, String projectId, String title) {
        APIResponse response = session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", title,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by the external Java Playwright agent API suite.",
                "visibility", "inherited"
        ));
        ApiDiagnostics.writeSnippet("agent-work-item-create-" + UniqueData.suffix(), "POST project work item for agent lifecycle", response);
        JsonNode workItem = session.requireJson(response, 201);
        cleanup.delete(session, "/api/v1/work-items/" + workItem.path("id").asText());
        return workItem;
    }

    private APIRequestContext workerRequest(Playwright playwright, String workerToken) {
        return playwright.request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL(config.backendBaseUrl().toString())
                .setExtraHTTPHeaders(Map.of(
                        "Accept", "application/json",
                        WORKER_TOKEN_HEADER, workerToken
                )));
    }

    private APIRequestContext callbackRequest(Playwright playwright, String callbackToken) {
        return playwright.request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL(config.backendBaseUrl().toString())
                .setExtraHTTPHeaders(Map.of(
                        "Accept", "application/json",
                        CALLBACK_HEADER, callbackToken
                )));
    }

    private JsonNode requireJson(APIResponse response, int status) {
        assertEquals(status, response.status(), response.text());
        return JsonSupport.read(response.text());
    }

    private void assertUnauthorized(APIResponse response, String snippetName) {
        ApiDiagnostics.writeSnippet(snippetName, snippetName, response);
        assertTrue(response.status() == 401 || response.status() == 403, response.text());
    }

    private boolean containsId(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) {
                return true;
            }
        }
        return false;
    }
}
