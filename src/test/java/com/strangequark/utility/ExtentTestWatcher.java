package com.strangequark.utility;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Optional;

public class ExtentTestWatcher implements TestWatcher, BeforeTestExecutionCallback {
    private static final ExtentReports extent = ExtentManager.getInstance();
    private static final ThreadLocal<ExtentTest> currentTest = new ThreadLocal<>();

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        String testName = context.getDisplayName();
        ExtentTest test = extent.createTest(testName);
        currentTest.set(test);
        test.info("Starting test: " + testName);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        currentTest.get().pass("✅ Test passed");
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        currentTest.get().fail("❌ Test failed: " + cause.getMessage());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        currentTest.get().skip("⚠️ Test aborted: " + cause.getMessage());
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        currentTest.get().skip("⏭️ Test skipped: " + reason.orElse("No reason provided"));
    }

    // Optional utility to get the current test in progress
    public static ExtentTest getCurrentTest() {
        return currentTest.get();
    }

    // Flush once at JVM shutdown
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(extent::flush));
    }
}
