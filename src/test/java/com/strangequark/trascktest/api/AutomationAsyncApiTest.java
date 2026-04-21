package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.strangequark.trascktest.support.LocalHttpReceiver;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.TestWorkspace;
import com.strangequark.trascktest.support.UniqueData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("automation")
@Tag("async")
class AutomationAsyncApiTest {
    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void automationAsyncWebhookEmailAndWorkerRoutesRequireAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);

            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-settings"),
                    "automation-worker-settings-unauthenticated");
            assertUnauthorized(request.get("/api/v1/workspaces/" + workspace.workspaceId() + "/webhooks"),
                    "webhooks-unauthenticated");
            assertUnauthorized(request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/automation-jobs/run-queued"),
                    "automation-run-queued-unauthenticated");
            assertUnauthorized(request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/webhook-deliveries/process"),
                    "webhook-deliveries-process-unauthenticated");
            assertUnauthorized(request.post("/api/v1/workspaces/" + workspace.workspaceId() + "/email-deliveries/process"),
                    "email-deliveries-process-unauthenticated");
            request.dispose();
        }
    }

    @Test
    void authenticatedAdminsCanRunAutomationWebhookEmailAndWorkerLifecycleAgainstLocalReceiver() {
        assumeTrue(config.canResolveAuthenticatedWorkspace(),
                "Set login/workspace/project env values or TRASCK_E2E_ALLOW_SETUP=true for automation async API coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create();
                AuthSession session = AuthSession.login(playwright, config);
                ApiCleanup cleanup = new ApiCleanup();
                LocalHttpReceiver receiver = LocalHttpReceiver.start(
                        config.localReceiverBindHost(),
                        config.localReceiverPort(),
                        config.localReceiverPublicBaseUrl()
                )) {
            TestWorkspace workspace = TestWorkspace.require(playwright, config);
            String suffix = UniqueData.suffix();
            String webhookSecret = "playwright-secret-" + suffix;
            JsonNode originalWorkerSettings = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-settings"), 200);
            JsonNode originalEmailSettings = session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/email-provider-settings"), 200);

            try {
                JsonNode updatedWorkerSettings = session.requireJson(session.patch(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-settings",
                        JsonSupport.object(
                                "automationJobsEnabled", true,
                                "webhookDeliveriesEnabled", true,
                                "emailDeliveriesEnabled", true,
                                "automationLimit", 5,
                                "webhookLimit", 5,
                                "emailLimit", 5,
                                "webhookMaxAttempts", 2,
                                "emailMaxAttempts", 2,
                                "webhookDryRun", false,
                                "emailDryRun", true
                        )
                ), 200);
                assertTrue(updatedWorkerSettings.path("automationJobsEnabled").asBoolean(), updatedWorkerSettings.toString());

                APIResponse emailSettingsResponse = session.put(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/email-provider-settings",
                        JsonSupport.object(
                                "provider", "maildev",
                                "fromEmail", "playwright@trasck.local",
                                "active", true
                        )
                );
                assumeTrue(!isProductionEmailProviderRejection(emailSettingsResponse),
                        "Maildev email provider is only selectable outside production-like profiles");
                JsonNode emailSettings = session.requireJson(emailSettingsResponse, 200);
                assertEquals("maildev", emailSettings.path("provider").asText(), emailSettings.toString());

                APIResponse createWebhookResponse = session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/webhooks",
                        JsonSupport.object(
                                "name", "Playwright local receiver " + suffix,
                                "url", receiver.url("/trasck-webhook").toString(),
                                "secret", webhookSecret,
                                "eventTypes", List.of("manual", "playwright.webhook"),
                                "enabled", true
                        )
                );
                ApiDiagnostics.writeSnippet("automation-local-webhook-create", "POST local-receiver webhook", createWebhookResponse);
                assumeTrue(!isOutboundPolicyRejection(createWebhookResponse),
                        "Set TRASCK_OUTBOUND_URL_ALLOWED_HOSTS=localhost:" + config.localReceiverPort()
                                + ",127.0.0.1:" + config.localReceiverPort()
                                + " before starting the backend for local receiver webhook E2E assertions");
                JsonNode webhook = session.requireJson(createWebhookResponse, 201);
                String webhookId = webhook.path("id").asText();
                String webhookSecretKeyId = webhook.path("secretKeyId").asText();
                cleanup.delete(session, "/api/v1/webhooks/" + webhookId);
                assertTrue(webhook.path("secretConfigured").asBoolean(), webhook.toString());
                assertTrue(webhookSecretKeyId.startsWith("whsec_"), webhook.toString());
                assertTrue(containsId(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/webhooks"), 200), webhookId));

                String webhookRuleId = createRule(session, cleanup, workspace, "Playwright webhook rule " + suffix);
                JsonNode condition = session.requireJson(session.post(
                        "/api/v1/automation-rules/" + webhookRuleId + "/conditions",
                        JsonSupport.object(
                                "conditionType", "always",
                                "config", JsonSupport.object("source", "playwright"),
                                "position", 0
                        )
                ), 201);
                String conditionId = condition.path("id").asText();
                JsonNode updatedCondition = session.requireJson(session.patch(
                        "/api/v1/automation-rules/" + webhookRuleId + "/conditions/" + conditionId,
                        JsonSupport.object(
                                "conditionType", "always",
                                "config", JsonSupport.object("source", "playwright-updated"),
                                "position", 1
                        )
                ), 200);
                assertEquals(1, updatedCondition.path("position").asInt(), updatedCondition.toString());

                JsonNode webhookAction = session.requireJson(session.post(
                        "/api/v1/automation-rules/" + webhookRuleId + "/actions",
                        JsonSupport.object(
                                "actionType", "webhook",
                                "executionMode", "async",
                                "position", 0,
                                "config", JsonSupport.object("webhookId", webhookId, "eventType", "playwright.webhook")
                        )
                ), 201);
                String webhookActionId = webhookAction.path("id").asText();
                JsonNode updatedWebhookAction = session.requireJson(session.patch(
                        "/api/v1/automation-rules/" + webhookRuleId + "/actions/" + webhookActionId,
                        JsonSupport.object(
                                "actionType", "webhook",
                                "executionMode", "async",
                                "position", 2,
                                "config", JsonSupport.object("webhookId", webhookId, "eventType", "playwright.webhook.updated")
                        )
                ), 200);
                assertEquals(2, updatedWebhookAction.path("position").asInt(), updatedWebhookAction.toString());

                JsonNode updatedRule = session.requireJson(session.patch(
                        "/api/v1/automation-rules/" + webhookRuleId,
                        JsonSupport.object(
                                "name", "Playwright webhook rule updated " + suffix,
                                "triggerType", "manual",
                                "triggerConfig", JsonSupport.object("updated", true),
                                "enabled", true
                        )
                ), 200);
                assertEquals("Playwright webhook rule updated " + suffix, updatedRule.path("name").asText(), updatedRule.toString());
                assertTrue(containsId(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/automation-rules"), 200), webhookRuleId));
                assertEquals(webhookRuleId, session.requireJson(session.get("/api/v1/automation-rules/" + webhookRuleId), 200).path("id").asText());

                JsonNode job = session.requireJson(session.post(
                        "/api/v1/automation-rules/" + webhookRuleId + "/execute",
                        JsonSupport.object(
                                "sourceEntityType", "project",
                                "sourceEntityId", workspace.projectId(),
                                "payload", JsonSupport.object("source", "playwright-webhook")
                        )
                ), 200);
                String jobId = job.path("id").asText();
                assertEquals("queued", job.path("status").asText(), job.toString());
                assertTrue(containsId(session.requireJson(session.get("/api/v1/automation-rules/" + webhookRuleId + "/jobs"), 200), jobId));
                assertEquals(jobId, session.requireJson(session.get("/api/v1/automation-jobs/" + jobId), 200).path("id").asText());

                JsonNode runJob = session.requireJson(session.post("/api/v1/automation-jobs/" + jobId + "/run", Map.of()), 200);
                assertEquals("succeeded", runJob.path("status").asText(), runJob.toString());
                JsonNode queuedDelivery = firstRow(session.requireJson(session.get("/api/v1/webhooks/" + webhookId + "/deliveries"), 200), "webhook delivery");
                String webhookDeliveryId = queuedDelivery.path("id").asText();
                String originalWebhookSecret = webhookSecret;
                String originalWebhookSecretKeyId = webhookSecretKeyId;
                assertEquals(originalWebhookSecretKeyId, queuedDelivery.path("signatureKeyId").asText(), queuedDelivery.toString());
                assertEquals(webhookDeliveryId, session.requireJson(session.get("/api/v1/webhook-deliveries/" + webhookDeliveryId), 200).path("id").asText());

                String rotatedWebhookSecret = webhookSecret + "-rotated";
                JsonNode renamedWebhook = session.requireJson(session.patch(
                        "/api/v1/webhooks/" + webhookId,
                        JsonSupport.object(
                                "name", "Playwright local receiver updated " + suffix,
                                "secret", rotatedWebhookSecret,
                                "eventTypes", List.of("manual", "playwright.webhook.updated"),
                                "enabled", true
                        )
                ), 200);
                assertEquals("Playwright local receiver updated " + suffix, renamedWebhook.path("name").asText(), renamedWebhook.toString());
                assertEquals(originalWebhookSecretKeyId, renamedWebhook.path("previousSecretKeyId").asText(), renamedWebhook.toString());
                assertFalse(renamedWebhook.path("previousSecretExpiresAt").asText().isBlank(), renamedWebhook.toString());
                assertNotEquals(originalWebhookSecretKeyId, renamedWebhook.path("secretKeyId").asText(), renamedWebhook.toString());
                webhookSecret = rotatedWebhookSecret;
                webhookSecretKeyId = renamedWebhook.path("secretKeyId").asText();

                JsonNode cancelledDelivery = session.requireJson(session.post("/api/v1/webhook-deliveries/" + webhookDeliveryId + "/cancel", Map.of()), 200);
                assertEquals("cancelled", cancelledDelivery.path("status").asText(), cancelledDelivery.toString());
                JsonNode retriedDelivery = session.requireJson(session.post("/api/v1/webhook-deliveries/" + webhookDeliveryId + "/retry", Map.of()), 200);
                assertEquals("queued", retriedDelivery.path("status").asText(), retriedDelivery.toString());

                receiver.clear();
                JsonNode deliveryRun = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/webhook-deliveries/process",
                        JsonSupport.object("limit", 5, "maxAttempts", 2, "dryRun", false)
                ), 200);
                assertTrue(deliveryRun.path("delivered").asInt() >= 1, deliveryRun.toString());
                assertTrue(receiver.awaitRequestCount(1, Duration.ofSeconds(5)), "Local receiver should receive the webhook delivery");
                LocalHttpReceiver.ReceivedRequest received = receiver.requests().get(0);
                assertEquals("POST", received.method());
                assertEquals("/trasck-webhook", received.path());
                assertEquals("playwright.webhook.updated", received.firstHeader("X-Trasck-Event-Type"));
                assertEquals(webhookId, received.firstHeader("X-Trasck-Webhook-Id"));
                assertEquals(webhookDeliveryId, received.firstHeader("X-Trasck-Webhook-Delivery-Id"));
                assertEquals(originalWebhookSecretKeyId, received.firstHeader("X-Trasck-Webhook-Signature-Key-Id"));
                String signatureTimestamp = received.firstHeader("X-Trasck-Webhook-Timestamp");
                assertFalse(signatureTimestamp.isBlank(), "Webhook signature timestamp should be present");
                assertEquals(
                        "sha256=" + hmacSha256(originalWebhookSecret, signatureTimestamp + "." + received.body()),
                        received.firstHeader("X-Trasck-Webhook-Signature")
                );
                assertTrue(received.body().contains(webhookRuleId), received.body());

                JsonNode queuedJob = session.requireJson(session.post(
                        "/api/v1/automation-rules/" + webhookRuleId + "/execute",
                        JsonSupport.object(
                                "sourceEntityType", "project",
                                "sourceEntityId", workspace.projectId(),
                                "payload", JsonSupport.object("source", "playwright-run-queued")
                        )
                ), 200);
                JsonNode queuedWorkerRun = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/automation-jobs/run-queued",
                        JsonSupport.object("limit", 5)
                ), 200);
                assertTrue(queuedWorkerRun.path("processed").asInt() >= 1, queuedWorkerRun.toString());
                assertTrue(containsId(session.requireJson(session.get("/api/v1/automation-rules/" + webhookRuleId + "/jobs"), 200), queuedJob.path("id").asText()));
                JsonNode rotatedQueuedDelivery = firstRow(session.requireJson(session.get("/api/v1/webhooks/" + webhookId + "/deliveries"), 200), "rotated webhook delivery");
                String rotatedDeliveryId = rotatedQueuedDelivery.path("id").asText();
                assertNotEquals(webhookDeliveryId, rotatedDeliveryId, rotatedQueuedDelivery.toString());
                assertEquals(webhookSecretKeyId, rotatedQueuedDelivery.path("signatureKeyId").asText(), rotatedQueuedDelivery.toString());
                receiver.clear();
                JsonNode rotatedDeliveryRun = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/webhook-deliveries/process",
                        JsonSupport.object("limit", 5, "maxAttempts", 2, "dryRun", false)
                ), 200);
                assertTrue(rotatedDeliveryRun.path("delivered").asInt() >= 1, rotatedDeliveryRun.toString());
                assertTrue(receiver.awaitRequestCount(1, Duration.ofSeconds(5)), "Local receiver should receive the rotated webhook delivery");
                LocalHttpReceiver.ReceivedRequest rotatedReceived = receiver.requests().get(0);
                assertEquals(rotatedDeliveryId, rotatedReceived.firstHeader("X-Trasck-Webhook-Delivery-Id"));
                assertEquals(webhookSecretKeyId, rotatedReceived.firstHeader("X-Trasck-Webhook-Signature-Key-Id"));
                String rotatedSignatureTimestamp = rotatedReceived.firstHeader("X-Trasck-Webhook-Timestamp");
                assertEquals(
                        "sha256=" + hmacSha256(webhookSecret, rotatedSignatureTimestamp + "." + rotatedReceived.body()),
                        rotatedReceived.firstHeader("X-Trasck-Webhook-Signature")
                );

                JsonNode emailRule = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/automation-rules",
                        JsonSupport.object(
                                "projectId", workspace.projectId(),
                                "name", "Playwright email rule " + suffix,
                                "triggerType", "manual",
                                "triggerConfig", JsonSupport.object("source", "playwright-email"),
                                "enabled", true
                        )
                ), 201);
                String emailRuleId = emailRule.path("id").asText();
                cleanup.delete(session, "/api/v1/automation-rules/" + emailRuleId);
                session.requireJson(session.post(
                        "/api/v1/automation-rules/" + emailRuleId + "/actions",
                        JsonSupport.object(
                                "actionType", "email",
                                "executionMode", "async",
                                "position", 0,
                                "config", JsonSupport.object(
                                        "toEmail", "playwright-recipient@example.test",
                                        "subject", "Playwright queued email " + suffix,
                                        "body", "Trasck queued this message from Playwright."
                                )
                        )
                ), 201);
                JsonNode emailJob = session.requireJson(session.post(
                        "/api/v1/automation-rules/" + emailRuleId + "/execute",
                        JsonSupport.object("sourceEntityType", "project", "sourceEntityId", workspace.projectId())
                ), 200);
                session.requireJson(session.post("/api/v1/automation-jobs/" + emailJob.path("id").asText() + "/run", Map.of()), 200);
                JsonNode emailDelivery = findByField(
                        session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/email-deliveries"), 200),
                        "subject",
                        "Playwright queued email " + suffix
                );
                String emailDeliveryId = emailDelivery.path("id").asText();
                assertEquals(emailDeliveryId, session.requireJson(session.get("/api/v1/email-deliveries/" + emailDeliveryId), 200).path("id").asText());
                assertEquals("cancelled", session.requireJson(session.post("/api/v1/email-deliveries/" + emailDeliveryId + "/cancel", Map.of()), 200).path("status").asText());
                assertEquals("queued", session.requireJson(session.post("/api/v1/email-deliveries/" + emailDeliveryId + "/retry", Map.of()), 200).path("status").asText());
                JsonNode emailRun = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/email-deliveries/process",
                        JsonSupport.object("limit", 5, "maxAttempts", 2, "dryRun", true)
                ), 200);
                assertTrue(emailRun.path("sent").asInt() >= 1, emailRun.toString());

                assertFalse(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-runs?workerType=automation"), 200).isEmpty());
                assertFalse(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-runs?workerType=webhook"), 200).isEmpty());
                assertFalse(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-runs?workerType=email"), 200).isEmpty());
                assertFalse(session.requireJson(session.get("/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-health?workerType=webhook"), 200).isEmpty());

                JsonNode retentionExport = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-runs/export?workerType=webhook&limit=10",
                        Map.of()
                ), 200);
                assertEquals("webhook", retentionExport.path("workerType").asText(), retentionExport.toString());
                JsonNode retentionPrune = session.requireJson(session.post(
                        "/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-runs/prune?workerType=email",
                        Map.of()
                ), 200);
                assertEquals("email", retentionPrune.path("workerType").asText(), retentionPrune.toString());

                assertNoContent(session.delete("/api/v1/automation-rules/" + webhookRuleId + "/actions/" + webhookActionId));
                assertNoContent(session.delete("/api/v1/automation-rules/" + webhookRuleId + "/conditions/" + conditionId));
            } finally {
                session.patch("/api/v1/workspaces/" + workspace.workspaceId() + "/automation-worker-settings", workerSettingsPatch(originalWorkerSettings));
                session.put("/api/v1/workspaces/" + workspace.workspaceId() + "/email-provider-settings", emailProviderSettingsRequest(originalEmailSettings));
            }
        }
    }

    private void assertUnauthorized(APIResponse response, String snippetName) {
        ApiDiagnostics.writeSnippet(snippetName, snippetName, response);
        assertTrue(response.status() == 401 || response.status() == 403, response.text());
    }

    private void assertNoContent(APIResponse response) {
        assertEquals(204, response.status(), response.text());
    }

    private String createRule(AuthSession session, ApiCleanup cleanup, TestWorkspace workspace, String name) {
        JsonNode rule = session.requireJson(session.post(
                "/api/v1/workspaces/" + workspace.workspaceId() + "/automation-rules",
                JsonSupport.object(
                        "projectId", workspace.projectId(),
                        "name", name,
                        "triggerType", "manual",
                        "triggerConfig", JsonSupport.object("source", "playwright"),
                        "enabled", true
                )
        ), 201);
        String ruleId = rule.path("id").asText();
        cleanup.delete(session, "/api/v1/automation-rules/" + ruleId);
        return ruleId;
    }

    private String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError("Could not calculate webhook signature", ex);
        }
    }

    private boolean isOutboundPolicyRejection(APIResponse response) {
        String body = response.text().toLowerCase();
        return response.status() == 400
                && (body.contains("outbound url policy") || body.contains("blocked private") || body.contains("host is blocked"));
    }

    private boolean isProductionEmailProviderRejection(APIResponse response) {
        return response.status() == 400 && response.text().toLowerCase().contains("maildev");
    }

    private boolean containsId(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode firstRow(JsonNode rows, String label) {
        if (!rows.isArray() || rows.isEmpty()) {
            throw new AssertionError("Expected at least one " + label + ": " + rows);
        }
        return rows.get(0);
    }

    private JsonNode findByField(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("Could not find row where " + field + " = " + value + ": " + rows);
    }

    private Map<String, Object> workerSettingsPatch(JsonNode settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        putIfPresent(values, settings, "automationJobsEnabled");
        putIfPresent(values, settings, "webhookDeliveriesEnabled");
        putIfPresent(values, settings, "emailDeliveriesEnabled");
        putIfPresent(values, settings, "importConflictResolutionEnabled");
        putIfPresent(values, settings, "importReviewExportsEnabled");
        putIfPresent(values, settings, "automationLimit");
        putIfPresent(values, settings, "webhookLimit");
        putIfPresent(values, settings, "emailLimit");
        putIfPresent(values, settings, "importConflictResolutionLimit");
        putIfPresent(values, settings, "importReviewExportLimit");
        putIfPresent(values, settings, "webhookMaxAttempts");
        putIfPresent(values, settings, "emailMaxAttempts");
        putIfPresent(values, settings, "webhookDryRun");
        putIfPresent(values, settings, "emailDryRun");
        putIfPresent(values, settings, "workerRunRetentionEnabled");
        putIfPresent(values, settings, "workerRunRetentionDays");
        putIfPresent(values, settings, "workerRunExportBeforePrune");
        putIfPresent(values, settings, "workerRunPruningAutomaticEnabled");
        putIfPresent(values, settings, "workerRunPruningIntervalMinutes");
        putIfPresent(values, settings, "workerRunPruningWindowStart");
        putIfPresent(values, settings, "workerRunPruningWindowEnd");
        putIfPresent(values, settings, "agentDispatchAttemptRetentionEnabled");
        putIfPresent(values, settings, "agentDispatchAttemptRetentionDays");
        putIfPresent(values, settings, "agentDispatchAttemptExportBeforePrune");
        putIfPresent(values, settings, "agentDispatchAttemptPruningAutomaticEnabled");
        putIfPresent(values, settings, "agentDispatchAttemptPruningIntervalMinutes");
        putIfPresent(values, settings, "agentDispatchAttemptPruningWindowStart");
        putIfPresent(values, settings, "agentDispatchAttemptPruningWindowEnd");
        return values;
    }

    private Map<String, Object> emailProviderSettingsRequest(JsonNode settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        putIfPresent(values, settings, "provider");
        putIfPresent(values, settings, "fromEmail");
        putIfPresent(values, settings, "smtpHost");
        putIfPresent(values, settings, "smtpPort");
        putIfPresent(values, settings, "smtpUsername");
        putIfPresent(values, settings, "smtpStartTlsEnabled");
        putIfPresent(values, settings, "smtpAuthEnabled");
        putIfPresent(values, settings, "active");
        return values;
    }

    private void putIfPresent(Map<String, Object> values, JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isBoolean()) {
            values.put(field, value.asBoolean());
        } else if (value.isInt() || value.isLong()) {
            values.put(field, value.asInt());
        } else {
            values.put(field, value.asText());
        }
    }
}
