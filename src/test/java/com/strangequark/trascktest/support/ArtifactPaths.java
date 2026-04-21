package com.strangequark.trascktest.support;

import java.nio.file.Path;

public final class ArtifactPaths {
    private static final Path ROOT = Path.of("test-results");

    private ArtifactPaths() {
    }

    public static Path trace(String testName) {
        return ROOT.resolve("traces").resolve(safeName(testName) + ".zip");
    }

    public static Path screenshot(String testName) {
        return ROOT.resolve("screenshots").resolve(safeName(testName) + ".png");
    }

    public static Path apiSnippet(String testName) {
        return ROOT.resolve("api").resolve(safeName(testName) + ".txt");
    }

    public static Path apiArtifact(String filename) {
        return ROOT.resolve("api").resolve(safeName(filename));
    }

    private static String safeName(String testName) {
        return (testName == null || testName.isBlank() ? "test" : testName)
                .replaceAll("[^a-zA-Z0-9._-]", "-")
                .replaceAll("-+", "-")
                .toLowerCase();
    }
}
