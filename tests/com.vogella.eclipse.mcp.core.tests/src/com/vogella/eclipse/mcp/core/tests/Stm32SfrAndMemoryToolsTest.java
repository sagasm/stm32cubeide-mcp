package com.vogella.eclipse.mcp.core.tests;

import java.util.Map;

import org.junit.jupiter.api.Test;

class Stm32SfrAndMemoryToolsTest {

    @Test
    void sfrReadRequiresASession() throws Exception {
        TestFixture.assertRefused(TestFixture.call("stm32_sfr_read", Map.of()), "sessionId");
    }

    @Test
    void memoryDumpRequiresASession() throws Exception {
        TestFixture.assertRefused(TestFixture.call("stm32_memory_dump", Map.of()), "sessionId");
    }

    @Test
    void memoryDumpRejectsAnUnknownFormat() throws Exception {
        TestFixture.assertRefused(TestFixture.call("stm32_memory_dump",
                Map.of("sessionId", "no-such-session", "address", "0x08000000", "format", "binary")), "binary");
    }
}
