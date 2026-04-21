package com.strangequark.trascktest.support;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Playwright;
import com.strangequark.trascktest.config.TrasckTestConfig;

public record TestWorkspace(String workspaceId, String projectId) {
    public static TestWorkspace require(TrasckTestConfig config) {
        assumeTrue(config.hasWorkspaceContext(),
                "Set TRASCK_E2E_WORKSPACE_ID and TRASCK_E2E_PROJECT_ID for workspace/project API coverage");
        return new TestWorkspace(config.workspaceId(), config.projectId());
    }

    public static TestWorkspace require(Playwright playwright, TrasckTestConfig config) {
        if (config.hasWorkspaceContext()) {
            return new TestWorkspace(config.workspaceId(), config.projectId());
        }
        SetupBootstrap.BootstrapContext context = SetupBootstrap.require(playwright, config);
        return new TestWorkspace(context.workspaceId(), context.projectId());
    }
}
