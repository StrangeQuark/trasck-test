package com.strangequark.trascktest.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class RuntimeChecks {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private RuntimeChecks() {
    }

    public static void requireHttpService(String serviceName, URI baseUrl, String path, Duration timeout) {
        URI target = baseUrl.resolve(path.startsWith("/") ? path : "/" + path);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;

        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(target)
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 500) {
                    return;
                }
                lastFailure = new IllegalStateException("HTTP " + response.statusCode());
            } catch (IOException ex) {
                lastFailure = ex;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(serviceName + " readiness check was interrupted", ex);
            }

            sleepBriefly(serviceName);
        }

        AssertionError error = new AssertionError(serviceName + " is not reachable at " + target
                + ". Start Trasck first or set the matching TRASCK_*_BASE_URL variable.");
        if (lastFailure != null) {
            error.initCause(lastFailure);
        }
        throw error;
    }

    private static void sleepBriefly(String serviceName) {
        try {
            Thread.sleep(250);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(serviceName + " readiness check was interrupted", ex);
        }
    }
}
