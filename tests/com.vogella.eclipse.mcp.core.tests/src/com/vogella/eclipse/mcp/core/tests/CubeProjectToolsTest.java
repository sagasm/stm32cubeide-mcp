package com.vogella.eclipse.mcp.core.tests;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CubeProjectToolsTest {

    @Test
    void projectInfoNeedsATarget() throws Exception {
        TestFixture.assertRefused(TestFixture.call("cubeide_project_info", Map.of()), "project");
    }

    @Test
    void projectInfoRejectsAnUnknownProject() throws Exception {
        TestFixture.assertRefused(TestFixture.call("cubeide_project_info",
                Map.of("project", "no-such-project")), "no-such-project");
    }

    @Test
    void regenerateNeedsATarget() throws Exception {
        TestFixture.assertRefused(TestFixture.call("cubeide_regenerate", Map.of()), "project");
    }
}
