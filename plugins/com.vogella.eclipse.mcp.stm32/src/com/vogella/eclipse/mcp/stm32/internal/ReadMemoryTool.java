package com.vogella.eclipse.mcp.stm32.internal;

import java.util.Map;

import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunch;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;

/**
 * Reads raw target memory through GDB/MI -data-read-memory-bytes.
 */
public final class ReadMemoryTool implements IMcpTool {

	@Override
	public String getName() {
		return "stm32_debug_read_memory"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Read raw bytes from a target address in a debug session started by stm32_debug_launch, via GDB/MI -data-read-memory-bytes. Works on Flash, RAM and SFR ranges when the target is suspended. Returns the bytes as a hex array plus ASCII and classic hexdump renderings. Use this to inspect memory-mapped registers or verify flashed code; address accepts 0x-prefixed hex or decimal."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["sessionId", "address"],
				  "properties": {
				    "sessionId": {"type":"string","description":"Session id from stm32_debug_launch or stm32_debug_control action=sessions."},
				    "address":   {"type":"string","description":"Start address, e.g. '0x08080000' or '134217728'."},
				    "length":    {"type":"integer","default":64,"description":"Number of bytes to read (1-4096)."},
				    "offset":    {"type":"integer","default":0,"description":"Byte offset relative to address."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String sessionId = args.getString("sessionId");
		String address = args.getString("address");
		if (sessionId == null) {
			return McpToolResult.error("The 'sessionId' argument is required.");
		}
		if (address == null) {
			return McpToolResult.error("The 'address' argument is required (0x-prefixed hex or decimal).");
		}
		ILaunch launch = DebugSessions.get(sessionId);
		if (launch == null) {
			return McpToolResult.error("No session '" + sessionId + "'. Use stm32_debug_control action=sessions to list them.");
		}
		if (!(launch instanceof GdbLaunch gdbLaunch)) {
			return McpToolResult.error("Session '" + sessionId + "' is not a DSF/GDB launch; memory reads need one.");
		}
		try {
			return McpToolResult.of(DebugSessions.readMemoryDsf(gdbLaunch, address,
					args.getInt("offset", 0, 0, Integer.MAX_VALUE),
					args.getInt("length", 64, 1, 4096)).toString());
		} catch (CoreException e) {
			return McpToolResult.error("Reading memory at " + address + " failed: " + e.getMessage());
		}
	}
}
