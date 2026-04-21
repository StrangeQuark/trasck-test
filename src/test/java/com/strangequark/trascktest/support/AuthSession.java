package com.strangequark.trascktest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import com.strangequark.trascktest.config.TrasckTestConfig;
import java.util.Map;

public final class AuthSession implements AutoCloseable {
    private final APIRequestContext request;
    private final JsonNode user;

    private AuthSession(APIRequestContext request, JsonNode user) {
        this.request = request;
        this.user = user;
    }

    public static AuthSession login(Playwright playwright, TrasckTestConfig config) {
        SetupBootstrap.BootstrapContext context = resolveLoginContext(playwright, config);
        APIRequestContext loginRequest = ApiRequestFactory.backend(playwright, config);
        JsonNode body;
        String accessToken;
        try {
            APIResponse login = loginRequest.post("/api/v1/auth/login", RequestOptions.create().setData(JsonSupport.object(
                    "identifier", context.loginIdentifier(),
                    "password", context.loginPassword()
            )));
            ApiDiagnostics.writeSnippet("auth-session-login", "POST /api/v1/auth/login", login);
            assertEquals(200, login.status(), login.text());

            body = JsonSupport.read(login.text());
            accessToken = body.path("accessToken").asText();
            if (accessToken.isBlank()) {
                throw new AssertionError("Login response did not include an accessToken");
            }
        } finally {
            loginRequest.dispose();
        }

        APIRequestContext bearerRequest = playwright.request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL(config.backendBaseUrl().toString())
                .setExtraHTTPHeaders(Map.of(
                        "Accept", "application/json",
                        "Authorization", "Bearer " + accessToken
                )));
        return new AuthSession(bearerRequest, body.path("user"));
    }

    private static SetupBootstrap.BootstrapContext resolveLoginContext(Playwright playwright, TrasckTestConfig config) {
        if (config.hasLoginCredentials()) {
            return new SetupBootstrap.BootstrapContext(
                    config.loginIdentifier(),
                    config.loginPassword(),
                    config.workspaceId(),
                    config.projectId()
            );
        }
        return SetupBootstrap.require(playwright, config);
    }

    public JsonNode user() {
        return user;
    }

    public String userId() {
        return user.path("id").asText();
    }

    public APIResponse get(String path) {
        return request.get(path);
    }

    public APIResponse post(String path, Object body) {
        return request.post(path, RequestOptions.create().setData(body));
    }

    public APIResponse postMultipart(String path, FormData form) {
        return request.post(path, RequestOptions.create().setMultipart(form));
    }

    public APIResponse patch(String path, Object body) {
        return request.patch(path, RequestOptions.create().setData(body));
    }

    public APIResponse put(String path, Object body) {
        return request.put(path, RequestOptions.create().setData(body));
    }

    public APIResponse delete(String path) {
        return request.delete(path);
    }

    public JsonNode requireJson(APIResponse response, int status) {
        assertEquals(status, response.status(), response.text());
        return JsonSupport.read(response.text());
    }

    @Override
    public void close() {
        request.dispose();
    }
}
