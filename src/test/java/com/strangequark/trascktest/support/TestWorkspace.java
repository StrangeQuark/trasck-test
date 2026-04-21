package com.strangequark.trascktest.support;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.strangequark.trascktest.config.TrasckTestConfig;

public record TestWorkspace(String workspaceId, String projectId) {
    public static TestWorkspace require(TrasckTestConfig config) {
        assumeTrue(config.hasWorkspaceContext(),
                "Set TRASCK_E2E_WORKSPACE_ID and TRASCK_E2E_PROJECT_ID for workspace/project API coverage");
        return new TestWorkspace(config.workspaceId(), config.projectId());
    }
}
