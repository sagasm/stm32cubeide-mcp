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
 * Breakpoint CRUD on a live DSF/GDB session via GDB/MI -break-* commands.
 */
public final class BreakpointsTool implements IMcpTool {

	@Override
	public String getName() {
		return "stm32_debug_breakpoints"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Manage breakpoints in a debug session started by stm32_debug_launch. action=list returns all GDB breakpoints (id/type/enabled/function/file/line/condition/hitCount); action=set inserts one at a location ('main', 'main.c:106', '0x08002b6c') with optional condition/ignoreCount/temporary/hardware; action=delete/enable/disable take comma-separated breakpoint ids; action=condition sets a condition on an existing id. Operations go straight to GDB/MI, so they work even while the target is running. Use list to confirm an insertion."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["sessionId", "action"],
				  "properties": {
				    "sessionId":    {"type":"string","description":"Session id from stm32_debug_launch or stm32_debug_control action=sessions."},
				    "action":       {"type":"string","enum":["list","set","delete","enable","disable","condition"],"description":"Operation to perform."},
				    "location":     {"type":"string","description":"For action=set: symbol, file:line or absolute address."},
				    "condition":    {"type":"string","description":"For action=set/condition: C expression that must be true to stop."},
				    "ids":          {"type":"string","description":"For delete/enable/disable/condition: GDB breakpoint number(s), comma separated."},
				    "temporary":    {"type":"boolean","default":false,"description":"For action=set: one-shot breakpoint."},
				    "hardware":     {"type":"boolean","default":false,"description":"For action=set: request a hardware breakpoint."},
				    "ignoreCount":  {"type":"integer","default":0,"description":"For action=set: number of hits to ignore before stopping."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String sessionId = args.getString("sessionId");
		String action = args.getString("action");
		if (sessionId == null) {
			return McpToolResult.error("The 'sessionId' argument is required.");
		}
		if (action == null) {
			return McpToolResult.error("The 'action' argument is required (list/set/delete/enable/disable/condition).");
		}
		ILaunch launch = DebugSessions.get(sessionId);
		if (launch == null) {
			return McpToolResult.error("No session '" + sessionId + "'. Use stm32_debug_control action=sessions to list them.");
		}
		if (!(launch instanceof GdbLaunch gdbLaunch)) {
			return McpToolResult.error("Session '" + sessionId + "' is not a DSF/GDB launch; breakpoints need one.");
		}
		try {
			return McpToolResult.of(DebugSessions.breakpointsDsf(gdbLaunch, action,
					args.getString("location"), args.getString("condition"), args.getString("ids"),
					args.getBoolean("temporary", false), args.getBoolean("hardware", false),
					args.getInt("ignoreCount", 0, 0, Integer.MAX_VALUE)).toString());
		} catch (CoreException e) {
			return McpToolResult.error("Breakpoint " + action + " failed: " + e.getMessage());
		}
	}
}
