package com.strangequark.trascktest.support;

import com.strangequark.trascktest.config.TrasckTestConfig;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

public final class ManagedProductionBackend implements AutoCloseable {
    private static final String DEV_JWT_SECRET = "dev-only-change-me-dev-only-change-me-dev-only-change-me-32";
    private static final int LOG_TAIL_LIMIT = 40_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private final PostgreSQLContainer<?> postgres;
    private final GenericContainer<?> redis;
    private final Process process;
    private final Thread logThread;
    private final URI baseUrl;
    private final Path logPath;
    private final StringBuilder logTail;

    private ManagedProductionBackend(
            PostgreSQLContainer<?> postgres,
            GenericContainer<?> redis,
            Process process,
            Thread logThread,
            URI baseUrl,
            Path logPath,
            StringBuilder logTail
    ) {
        this.postgres = postgres;
        this.redis = redis;
        this.process = process;
        this.logThread = logThread;
        this.baseUrl = baseUrl;
        this.logPath = logPath;
        this.logTail = logTail;
    }

    public static ManagedProductionBackend start(
            TrasckTestConfig config,
            String testName,
            Map<String, String> environmentOverrides
    ) {
        StartedDependencies dependencies = startDependencies();
        int port = findFreePort();
        Path logPath = ArtifactPaths.runtimeLog(testName);
        StringBuilder logTail = new StringBuilder();
        Process process = null;
        Thread logThread = null;
        try {
            process = startBackendProcess(config, dependencies, port, logPath, logTail, environmentOverrides);
            logThread = captureLog(process.getInputStream(), logPath, logTail);
            ManagedProductionBackend backend = new ManagedProductionBackend(
                    dependencies.postgres(),
                    dependencies.redis(),
                    process,
                    logThread,
                    URI.create("http://localhost:" + port),
                    logPath,
                    logTail
            );
            backend.waitUntilReady(config.managedProductionStartupTimeout());
            return backend;
        } catch (RuntimeException | Error ex) {
            stopProcess(process, logThread);
            dependencies.close();
            throw ex;
        }
    }

    public static StartupFailure expectStartupFailure(
            TrasckTestConfig config,
            String testName,
            Map<String, String> environmentOverrides
    ) {
        StartedDependencies dependencies = startDependencies();
        int port = findFreePort();
        Path logPath = ArtifactPaths.runtimeLog(testName);
        StringBuilder logTail = new StringBuilder();
        Process process = null;
        Thread logThread = null;
        try {
            process = startBackendProcess(config, dependencies, port, logPath, logTail, environmentOverrides);
            logThread = captureLog(process.getInputStream(), logPath, logTail);
            boolean exited = process.waitFor(config.managedProductionStartupTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                throw new AssertionError("Managed production backend did not fail startup within "
                        + config.managedProductionStartupTimeout() + ". Log: " + logPath + "\n" + tail(logTail));
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                throw new AssertionError("Managed production backend exited successfully when startup failure was expected. Log: "
                        + logPath + "\n" + tail(logTail));
            }
            joinLogThread(logThread);
            return new StartupFailure(exitCode, logPath, tail(logTail));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for managed production backend startup failure", ex);
        } finally {
            stopProcess(process, logThread);
            dependencies.close();
        }
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public Path logPath() {
        return logPath;
    }

    public static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new AssertionError("Unable to allocate a free local port", ex);
        }
    }

    @Override
    public void close() {
        stopProcess(process, logThread);
        redis.stop();
        postgres.stop();
    }

    private void waitUntilReady(Duration timeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("Managed production backend exited before readiness. Log: "
                        + logPath + "\n" + tail(logTail));
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/api/trasck/health"))
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
                lastFailure = new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            } catch (IOException ex) {
                lastFailure = ex;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for managed production backend readiness", ex);
            }
            sleepBriefly();
        }

        AssertionError error = new AssertionError("Managed production backend did not become ready at " + baseUrl
                + " within " + timeout + ". Log: " + logPath + "\n" + tail(logTail));
        if (lastFailure != null) {
            error.initCause(lastFailure);
        }
        throw error;
    }

    private static StartedDependencies startDependencies() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:13.1-alpine")
                .withDatabaseName("trasck_managed_prod_test")
                .withUsername("trasck")
                .withPassword("prod-test-database-password-123456");
        GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);
        try {
            postgres.start();
            redis.start();
            return new StartedDependencies(postgres, redis);
        } catch (RuntimeException ex) {
            redis.stop();
            postgres.stop();
            throw ex;
        }
    }

    private static Process startBackendProcess(
            TrasckTestConfig config,
            StartedDependencies dependencies,
            int port,
            Path logPath,
            StringBuilder logTail,
            Map<String, String> environmentOverrides
    ) {
        Path backendDirectory = config.managedProductionBackendDirectory();
        Path mvnw = backendDirectory.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        if (!Files.isRegularFile(mvnw)) {
            throw new AssertionError("Managed production backend project was not found at " + backendDirectory
                    + ". Set TRASCK_E2E_BACKEND_PROJECT_DIR to the Trasck backend repo.");
        }
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, "");
            ProcessBuilder builder = new ProcessBuilder(
                    mvnw.toString(),
                    "-q",
                    "-DskipTests",
                    "spring-boot:run"
            );
            builder.directory(backendDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.environment().putAll(secureEnvironment(dependencies, port));
            builder.environment().putAll(environmentOverrides == null ? Map.of() : environmentOverrides);
            Process process = builder.start();
            appendLog(logTail, "Started managed production backend process from " + backendDirectory
                    + " on port " + port + "\n");
            return process;
        } catch (IOException ex) {
            throw new AssertionError("Unable to start managed production backend. Log: " + logPath, ex);
        }
    }

    private static Map<String, String> secureEnvironment(StartedDependencies dependencies, int port) {
        Map<String, String> env = new HashMap<>();
        env.put("SPRING_PROFILES_ACTIVE", "prod");
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("SPRING_DATASOURCE_URL", dependencies.postgres().getJdbcUrl());
        env.put("SPRING_DATASOURCE_USERNAME", dependencies.postgres().getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", dependencies.postgres().getPassword());
        env.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "validate");
        env.put("SPRING_FLYWAY_ENABLED", "true");
        env.put("SPRING_DATA_REDIS_HOST", dependencies.redis().getHost());
        env.put("SPRING_DATA_REDIS_PORT", String.valueOf(dependencies.redis().getMappedPort(6379)));
        env.put("SPRING_DATA_REDIS_PASSWORD", "");
        env.put("TRASCK_JWT_SECRET", "prod-managed-jwt-secret-that-is-long-enough-123456");
        env.put("TRASCK_SECRETS_ENCRYPTION_KEY", "prod-managed-encryption-key-material-123456");
        env.put("TRASCK_OAUTH_ASSERTION_SECRET", "prod-managed-oauth-assertion-secret-123456");
        env.put("TRASCK_AUTH_COOKIE_SECURE", "true");
        env.put("TRASCK_SECURITY_RATE_LIMIT_STORE", "redis");
        env.put("TRASCK_OAUTH_SUCCESS_REDIRECT", "https://app.example.test/auth/callback");
        env.put("TRASCK_OAUTH_GITHUB_CLIENT_ID", "disabled-github-client-id");
        env.put("TRASCK_OAUTH_GITHUB_CLIENT_SECRET", "disabled-github-client-secret");
        env.put("TRASCK_OAUTH_GOOGLE_CLIENT_ID", "disabled-google-client-id");
        env.put("TRASCK_OAUTH_GOOGLE_CLIENT_SECRET", "disabled-google-client-secret");
        env.put("TRASCK_OAUTH_GITLAB_CLIENT_ID", "disabled-gitlab-client-id");
        env.put("TRASCK_OAUTH_GITLAB_CLIENT_SECRET", "disabled-gitlab-client-secret");
        env.put("TRASCK_OAUTH_MICROSOFT_CLIENT_ID", "disabled-microsoft-client-id");
        env.put("TRASCK_OAUTH_MICROSOFT_CLIENT_SECRET", "disabled-microsoft-client-secret");
        env.put("CORS_ALLOWED_ORIGINS", "https://app.example.test");
        env.put("TRASCK_EVENTS_OUTBOX_FIXED_DELAY_MS", "600000");
        env.put("TRASCK_AUTOMATION_WORKERS_FIXED_DELAY_MS", "600000");
        env.put("TRASCK_WEBHOOK_PREVIOUS_SECRET_OVERLAP", "PT720H");
        env.put("SPRING_OUTPUT_ANSI_ENABLED", "never");
        return env;
    }

    public static Map<String, String> unsafeProductionDefaultOverrides() {
        return Map.of(
                "TRASCK_JWT_SECRET", DEV_JWT_SECRET,
                "TRASCK_SECRETS_ENCRYPTION_KEY", DEV_JWT_SECRET,
                "TRASCK_OAUTH_ASSERTION_SECRET", "dev-only-change-me-oauth-assertion-secret-123456",
                "TRASCK_AUTH_COOKIE_SECURE", "false",
                "TRASCK_SECURITY_RATE_LIMIT_STORE", "database",
                "CORS_ALLOWED_ORIGINS", "http://localhost:8080",
                "TRASCK_OAUTH_SUCCESS_REDIRECT", "http://localhost:8080/auth/callback"
        );
    }

    private static Thread captureLog(InputStream inputStream, Path logPath, StringBuilder logTail) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    BufferedWriter writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    appendLog(logTail, line + "\n");
                }
            } catch (IOException ex) {
                appendLog(logTail, "Unable to capture managed production backend log: " + ex.getMessage() + "\n");
            }
        }, "managed-production-backend-log");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void stopProcess(Process process, Thread logThread) {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        joinLogThread(logThread);
    }

    private static void joinLogThread(Thread logThread) {
        if (logThread == null) {
            return;
        }
        try {
            logThread.join(2_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static synchronized void appendLog(StringBuilder logTail, String value) {
        logTail.append(value);
        if (logTail.length() > LOG_TAIL_LIMIT) {
            logTail.delete(0, logTail.length() - LOG_TAIL_LIMIT);
        }
    }

    private static synchronized String tail(StringBuilder logTail) {
        return logTail.toString();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for managed production backend", ex);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record StartedDependencies(PostgreSQLContainer<?> postgres, GenericContainer<?> redis) implements AutoCloseable {
        @Override
        public void close() {
            redis.stop();
            postgres.stop();
        }
    }

    public record StartupFailure(int exitCode, Path logPath, String logTail) {
    }
}
