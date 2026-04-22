package com.strangequark.trascktest.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiCleanup;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.AuthSession;
import com.strangequark.trascktest.support.JsonSupport;
import com.strangequark.trascktest.support.SetupBootstrap;
import com.strangequark.trascktest.support.TestWorkspace;
import java.util.List;
import java.util.Map;

abstract class RealFrontendWorkflowSupport {
    protected final TrasckTestConfig config = TrasckTestConfig.load();

    protected JsonNode createStory(AuthSession session, ApiCleanup cleanup, String projectId, String title) {
        APIResponse response = session.post("/api/v1/projects/" + projectId + "/work-items", JsonSupport.object(
                "typeKey", "story",
                "title", title,
                "reporterId", session.userId(),
                "descriptionMarkdown", "Created by a real-backend Java Playwright browser workflow.",
                "visibility", "inherited"
        ));
        ApiDiagnostics.writeSnippet("remaining-real-work-item-create", "POST project work item for remaining browser workflow", response);
        JsonNode workItem = session.requireJson(response, 201);
        cleanup.delete(session, "/api/v1/work-items/" + workItem.path("id").asText());
        return workItem;
    }

    protected void loginThroughUi(Page page, SetupBootstrap.BootstrapContext login) {
        page.navigate("/auth");
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign In"))).isVisible();
        page.getByLabel("Identifier").fill(login.loginIdentifier());
        page.getByLabel("Password").fill(login.loginPassword());
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
        assertThat(page.getByText("human").first()).isVisible();
        assertNull(page.evaluate("() => localStorage.getItem('trasck.accessToken')"));
    }

    protected void installFrontendContext(Page page, TestWorkspace workspace) {
        String script = "localStorage.setItem('trasck.apiBaseUrl', '" + jsString(config.backendBaseUrl().toString()) + "');"
                + "localStorage.setItem('trasck.workspaceId', '" + jsString(workspace.workspaceId()) + "');"
                + "localStorage.setItem('trasck.projectId', '" + jsString(workspace.projectId()) + "');";
        page.addInitScript(script);
    }

    protected Locator panel(Page page, String title) {
        return page.locator("xpath=//section[contains(concat(' ', normalize-space(@class), ' '), ' panel ')][.//h2[normalize-space()="
                + xpathLiteral(title) + "]]").first();
    }

    protected JsonNode requireRecordByField(AuthSession session, String path, String field, String value) {
        JsonNode rows = session.requireJson(session.get(path), 200);
        for (JsonNode row : rowItems(rows)) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("Could not find record with " + field + "=" + value + " from " + path + ": " + rows);
    }

    protected JsonNode waitForRecordByField(AuthSession session, String path, String field, String value) {
        long deadline = System.currentTimeMillis() + 5_000;
        AssertionError lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return requireRecordByField(session, path, field, value);
            } catch (AssertionError ex) {
                lastFailure = ex;
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return requireRecordByField(session, path, field, value);
    }

    protected JsonNode requireImportJobByConfigSource(AuthSession session, String workspaceId, String source) {
        JsonNode rows = session.requireJson(session.get("/api/v1/workspaces/" + workspaceId + "/import-jobs"), 200);
        for (JsonNode row : rowItems(rows)) {
            if (source.equals(row.path("config").path("source").asText())) {
                return row;
            }
        }
        throw new AssertionError("Could not find import job with config.source=" + source + ": " + rows);
    }

    protected JsonNode waitForImportJobByConfigSource(AuthSession session, String workspaceId, String source) {
        long deadline = System.currentTimeMillis() + 5_000;
        AssertionError lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return requireImportJobByConfigSource(session, workspaceId, source);
            } catch (AssertionError ex) {
                lastFailure = ex;
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return requireImportJobByConfigSource(session, workspaceId, source);
    }

    protected List<JsonNode> workItemsByTitle(AuthSession session, String projectId, String title) {
        JsonNode page = session.requireJson(session.get("/api/v1/projects/" + projectId + "/work-items?limit=100"), 200);
        java.util.ArrayList<JsonNode> matches = new java.util.ArrayList<>();
        for (JsonNode item : rowItems(page)) {
            if (title.equals(item.path("title").asText())) {
                matches.add(item);
            }
        }
        return matches;
    }

    protected void deleteWorkItemsByTitle(AuthSession session, String projectId, String title) {
        for (JsonNode item : workItemsByTitle(session, projectId, title)) {
            int status = session.delete("/api/v1/work-items/" + item.path("id").asText()).status();
            if (status != 200 && status != 204 && status != 404) {
                throw new AssertionError("Cleanup DELETE work item returned HTTP " + status);
            }
        }
    }

    protected void postIgnoringStatus(AuthSession session, String path) {
        session.post(path, Map.of());
    }

    protected Iterable<JsonNode> rowItems(JsonNode rows) {
        JsonNode items = rows.path("items");
        if (items.isArray()) {
            return items;
        }
        if (rows.isArray()) {
            return rows;
        }
        throw new AssertionError("Expected array or cursor page with items: " + rows);
    }

    protected String jsString(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    protected String xpathLiteral(String value) {
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\"", "") + "\"";
    }
}
