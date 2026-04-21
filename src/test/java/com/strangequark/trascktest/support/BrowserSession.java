package com.strangequark.trascktest.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.strangequark.trascktest.config.TrasckTestConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BrowserSession implements AutoCloseable {
    private final String testName;
    private final BrowserContext context;
    private final Page page;
    private final List<String> consoleErrors = new ArrayList<>();

    private BrowserSession(String testName, BrowserContext context, Page page) {
        this.testName = testName;
        this.context = context;
        this.page = page;
    }

    public static BrowserSession start(Browser browser, TrasckTestConfig config, String testName) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(config.frontendBaseUrl().toString()));
        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
        Page page = context.newPage();
        BrowserSession session = new BrowserSession(testName, context, page);
        page.onConsoleMessage(message -> {
            if ("error".equals(message.type())) {
                session.consoleErrors.add(message.text() + " at " + message.location());
            }
        });
        page.onPageError(error -> session.consoleErrors.add(String.valueOf(error)));
        return session;
    }

    public Page page() {
        return page;
    }

    public void assertNoConsoleErrors() {
        assertTrue(consoleErrors.isEmpty(), "Browser console errors: " + consoleErrors);
    }

    public void screenshot() {
        Path path = ArtifactPaths.screenshot(testName);
        try {
            Files.createDirectories(path.getParent());
            page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true));
        } catch (RuntimeException | java.io.IOException ex) {
            throw new AssertionError("Unable to capture screenshot to " + path, ex);
        }
    }

    @Override
    public void close() {
        Path tracePath = ArtifactPaths.trace(testName);
        try {
            Files.createDirectories(tracePath.getParent());
            context.tracing().stop(new Tracing.StopOptions().setPath(tracePath));
        } catch (RuntimeException | java.io.IOException ex) {
            throw new AssertionError("Unable to write Playwright trace to " + tracePath, ex);
        } finally {
            context.close();
        }
    }
}
