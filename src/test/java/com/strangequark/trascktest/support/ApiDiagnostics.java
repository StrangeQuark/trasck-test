package com.strangequark.trascktest.support;

import com.microsoft.playwright.APIResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ApiDiagnostics {
    private static final int BODY_LIMIT = 4_000;

    private ApiDiagnostics() {
    }

    public static void writeSnippet(String testName, String requestLine, APIResponse response) {
        Path path = ArtifactPaths.apiSnippet(testName);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, snippet(requestLine, response));
        } catch (IOException ex) {
            throw new AssertionError("Unable to write API diagnostic snippet to " + path, ex);
        }
    }

    private static String snippet(String requestLine, APIResponse response) {
        String body = redact(response.text());
        if (body.length() > BODY_LIMIT) {
            body = body.substring(0, BODY_LIMIT) + "\n...truncated...";
        }
        return requestLine
                + "\nstatus: " + response.status()
                + "\nheaders: " + safeHeaders(response.headers())
                + "\nbody:\n" + body;
    }

    private static Map<String, String> safeHeaders(Map<String, String> headers) {
        Map<String, String> safe = new LinkedHashMap<>(headers);
        safe.replaceAll((key, value) -> sensitiveHeader(key) ? "[redacted]" : redact(value));
        return safe;
    }

    private static boolean sensitiveHeader(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("token");
    }

    private static String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replaceAll("(?i)(\"(?:accessToken|token|password|secret|authorization|cookie)\"\\s*:\\s*\")([^\"]+)(\")", "$1[redacted]$3")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer [redacted]");
    }
}
