package com.vogella.eclipse.mcp.core.tests;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CdtNavigationToolsTest {

    @Test
    void callHierarchyRequiresAName() throws Exception {
        TestFixture.assertRefused(TestFixture.call("cdt_call_hierarchy", Map.of()), "name");
    }

    @Test
    void callHierarchyRejectsAnUnknownDirection() throws Exception {
        TestFixture.assertRefused(TestFixture.call("cdt_call_hierarchy",
                Map.of("name", "main", "direction", "sideways")), "sideways");
    }

    @Test
    void outlineRequiresAPath() throws Exception {
        TestFixture.assertRefused(TestFixture.call("cdt_outline", Map.of()), "path");
    }

    @Test
    void outlineRejectsAnUnknownKindFilter() throws Exception {
        TestFixture.assertRefused(TestFixture.call("cdt_outline",
                Map.of("path", "kz036/Core/Src/main.c", "kindFilter", "class")), "class");
    }
}
