package com.vogella.eclipse.mcp.stm32.internal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunch;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Dumps target memory by symbol name or raw address in a suspended session.
 */
public final class MemoryDumpTool implements IMcpTool {

    private static final Set<String> FORMATS = Set.of("all", "hex", "ascii", "hexdump", "bytes");

    @Override
    public String getName() {
        return "stm32_memory_dump"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Dumps target memory in a debug session started by stm32_debug_launch. Give either a "
                + "C 'symbol' (resolved to its address by GDB, including &'ed globals and arrays) or "
                + "a raw 'address'; when both are present the symbol wins. Wraps the same MI "
                + "-data-read-memory-bytes read as stm32_debug_read_memory, with a 'format' switch "
                + "so a large dump can be kept to just hex, ASCII, hexdump or a byte array."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "required": ["sessionId"],
                  "properties": {
                    "sessionId": {"type":"string","description":"Session id from stm32_debug_launch or stm32_debug_control action=sessions."},
                    "symbol":    {"type":"string","description":"C symbol to resolve, e.g. 'SystemCoreClock' or a global buffer."},
                    "address":   {"type":"string","description":"Start address if no symbol: '0x08000000' or decimal."},
                    "length":    {"type":"integer","default":64,"description":"Number of bytes to read (1-4096)."},
                    "offset":    {"type":"integer","default":0,"description":"Byte offset from the resolved address."},
                    "format":    {"type":"string","enum":["all","hex","ascii","hexdump","bytes"],"default":"all"}
                  },
                  "additionalProperties": false
                }"""; //$NON-NLS-1$
    }

    @Override
    public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
        ToolArguments args = ToolArguments.of(arguments);
        String sessionId = args.getString("sessionId"); //$NON-NLS-1$
        if (sessionId == null) {
            return McpToolResult.error("The 'sessionId' argument is required."); //$NON-NLS-1$
        }
        String symbol = args.getString("symbol"); //$NON-NLS-1$
        String address = args.getString("address"); //$NON-NLS-1$
        if ((symbol == null || symbol.isBlank()) && (address == null || address.isBlank())) {
            return McpToolResult.error("Provide either 'symbol' or 'address'."); //$NON-NLS-1$
        }
        String format = args.getString("format", "all").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        if (!FORMATS.contains(format)) {
            return McpToolResult.error("Unknown format '" + format + "'; use all/hex/ascii/hexdump/bytes."); //$NON-NLS-1$
        }
        ILaunch launch = DebugSessions.get(sessionId);
        if (launch == null) {
            return McpToolResult.error("No session '" + sessionId + "'. Use stm32_debug_control action=sessions to list them."); //$NON-NLS-1$
        }
        if (!(launch instanceof GdbLaunch gdbLaunch)) {
            return McpToolResult.error("Session '" + sessionId + "' is not a DSF/GDB launch; memory dumps need one."); //$NON-NLS-1$
        }

        String effectiveAddress;
        try {
            if (symbol != null && !symbol.isBlank()) {
                long resolved = DebugSessions.resolveAddressDsf(gdbLaunch, symbol);
                effectiveAddress = "0x" + Long.toHexString(resolved); //$NON-NLS-1$
            } else {
                effectiveAddress = address;
            }
        } catch (CoreException e) {
            return McpToolResult.error("Resolving symbol '" + symbol + "' failed: " + e.getMessage()); //$NON-NLS-1$
        }

        int length = args.getInt("length", 64, 1, 4096); //$NON-NLS-1$
        int offset = args.getInt("offset", 0, 0, Integer.MAX_VALUE); //$NON-NLS-1$
        try {
            JsonObject raw = DebugSessions.readMemoryDsf(gdbLaunch, effectiveAddress, offset, length);
            JsonObject result = new JsonObject()
                    .put("sessionId", sessionId) //$NON-NLS-1$
                    .put("symbol", symbol) //$NON-NLS-1$
                    .put("address", effectiveAddress) //$NON-NLS-1$
                    .put("offset", Integer.valueOf(offset)) //$NON-NLS-1$
                    .put("length", Integer.valueOf(length)) //$NON-NLS-1$
                    .put("format", format) //$NON-NLS-1$
                    .put("success", raw.remove("success")); //$NON-NLS-1$
            if ("all".equals(format) || "hex".equals(format) || "bytes".equals(format)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                result.put("bytes", raw.remove("bytes")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if ("all".equals(format) || "ascii".equals(format)) { //$NON-NLS-1$ //$NON-NLS-2$
                result.put("ascii", raw.remove("ascii")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if ("all".equals(format) || "hexdump".equals(format)) { //$NON-NLS-1$ //$NON-NLS-2$
                result.put("hexdump", raw.remove("hexdump")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Object error = raw.remove("error"); //$NON-NLS-1$
            if (error != null) {
                result.put("error", error); //$NON-NLS-1$
            }
            return McpToolResult.of(result.toString());
        } catch (CoreException e) {
            return McpToolResult.error("Reading memory at " + effectiveAddress + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }
}
