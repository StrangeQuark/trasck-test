package com.strangequark.trascktest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.strangequark.trascktest.config.TrasckTestConfig;
import com.strangequark.trascktest.support.ApiDiagnostics;
import com.strangequark.trascktest.support.ApiRequestFactory;
import com.strangequark.trascktest.support.RuntimeChecks;
import com.strangequark.trascktest.support.SetupBootstrap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("auth")
@Tag("smoke")
class AuthSessionApiTest {
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

    private final TrasckTestConfig config = TrasckTestConfig.load();

    @Test
    void currentUserRequiresAuthentication() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);
            APIResponse response = request.get("/api/v1/auth/me");
            ApiDiagnostics.writeSnippet("auth-me-unauthenticated", "GET /api/v1/auth/me", response);

            assertTrue(response.status() == 401 || response.status() == 403, response.text());
            request.dispose();
        }
    }

    @Test
    void logoutRegisterAndOauthRoutesRejectUnsafeInputs() {
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            APIRequestContext request = ApiRequestFactory.backend(playwright, config);

            APIResponse logout = request.post("/api/v1/auth/logout");
            ApiDiagnostics.writeSnippet("auth-logout-open-route", "POST /api/v1/auth/logout", logout);
            assertEquals(204, logout.status(), logout.text());
            assertTrue(logout.headers().getOrDefault("set-cookie", "").contains("trasck_access_token="),
                    logout.headers().toString());

            APIResponse invalidRegister = request.post("/api/v1/auth/register", RequestOptions.create().setData(Map.of(
                    "email", "invalid-register@example.test",
                    "username", "invalid-register",
                    "displayName", "Invalid Register",
                    "password", "correct-horse-battery-staple",
                    "invitationToken", "not-a-real-invitation-token"
            )));
            ApiDiagnostics.writeSnippet("auth-register-invalid-invitation", "POST /api/v1/auth/register invalid invitation", invalidRegister);
            assertEquals(403, invalidRegister.status(), invalidRegister.text());

            APIResponse invalidOauth = request.post("/api/v1/auth/oauth/login", RequestOptions.create().setData(Map.of(
                    "provider", "github",
                    "providerSubject", "playwright-subject",
                    "providerEmail", "oauth-playwright@example.test",
                    "emailVerified", true,
                    "assertion", "invalid-assertion"
            )));
            ApiDiagnostics.writeSnippet("auth-oauth-invalid-assertion", "POST /api/v1/auth/oauth/login invalid assertion", invalidOauth);
            assertTrue(invalidOauth.status() == 401 || invalidOauth.status() == 503, invalidOauth.text());
            request.dispose();
        }
    }

    @Test
    void loginEstablishesCookieAndBearerSessionsWhenCredentialsAreProvided() {
        assumeTrue(config.hasLoginCredentials() || config.allowSetupBootstrap(),
                "Set TRASCK_E2E_LOGIN_IDENTIFIER/TRASCK_E2E_LOGIN_PASSWORD or TRASCK_E2E_ALLOW_SETUP=true to run login smoke coverage");
        RuntimeChecks.requireHttpService("Trasck backend", config.backendBaseUrl(), "/api/trasck/health", config.timeout());

        try (Playwright playwright = Playwright.create()) {
            String identifier = config.loginIdentifier();
            String password = config.loginPassword();
            if (!config.hasLoginCredentials()) {
                var loginContext = SetupBootstrap.require(playwright, config);
                identifier = loginContext.loginIdentifier();
                password = loginContext.loginPassword();
            }
            APIRequestContext cookieRequest = ApiRequestFactory.backend(playwright, config);
            APIResponse login = cookieRequest.post("/api/v1/auth/login", RequestOptions.create().setData(Map.of(
                    "identifier", identifier,
                    "password", password
            )));
            ApiDiagnostics.writeSnippet("auth-login", "POST /api/v1/auth/login", login);
            assertEquals(200, login.status(), login.text());

            APIResponse meByCookie = cookieRequest.get("/api/v1/auth/me");
            ApiDiagnostics.writeSnippet("auth-me-cookie", "GET /api/v1/auth/me using cookie", meByCookie);
            assertEquals(200, meByCookie.status(), meByCookie.text());

            String accessToken = accessToken(login);
            assertFalse(accessToken.isBlank(), "Login response did not include an accessToken");
            APIRequestContext bearerRequest = playwright.request().newContext(new APIRequest.NewContextOptions()
                    .setBaseURL(config.backendBaseUrl().toString())
                    .setExtraHTTPHeaders(Map.of("Authorization", "Bearer " + accessToken)));
            APIResponse meByBearer = bearerRequest.get("/api/v1/auth/me");
            ApiDiagnostics.writeSnippet("auth-me-bearer", "GET /api/v1/auth/me using Bearer", meByBearer);
            assertEquals(200, meByBearer.status(), meByBearer.text());

            APIResponse missingCsrf = cookieRequest.post("/api/v1/auth/tokens/personal", RequestOptions.create().setData(Map.of(
                    "name", "csrf-smoke-token"
            )));
            ApiDiagnostics.writeSnippet("auth-cookie-missing-csrf", "POST /api/v1/auth/tokens/personal without CSRF", missingCsrf);
            assertEquals(403, missingCsrf.status(), missingCsrf.text());
            bearerRequest.dispose();
            cookieRequest.dispose();
        }
    }

    private String accessToken(APIResponse login) {
        Matcher matcher = ACCESS_TOKEN_PATTERN.matcher(login.text());
        return matcher.find() ? matcher.group(1) : "";
    }
}
