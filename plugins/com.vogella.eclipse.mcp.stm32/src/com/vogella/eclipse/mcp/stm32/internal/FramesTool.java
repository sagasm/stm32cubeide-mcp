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
 * Reads the full call stack of a suspended debug session: one entry per frame
 * with its function, source file, line and (optionally) locals and arguments.
 */
public final class FramesTool implements IMcpTool {

	@Override
	public String getName() {
		return "stm32_debug_frames"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Read the call stack of a suspended session started by stm32_debug_launch. Returns the frames from innermost (level 0) outward, each with function name, source file, line and module; includeVariables=true adds each frame's locals and arguments via GDB/MI -stack-list-locals/-arguments. Use this to see the whole backtrace, not just the top frame reported by stm32_debug_control."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["sessionId"],
				  "properties": {
				    "sessionId":        {"type":"string","description":"Session id from stm32_debug_launch or stm32_debug_control action=sessions."},
				    "thread":           {"type":"integer","default":0,"description":"Zero-based thread index to read."},
				    "includeVariables": {"type":"boolean","default":true,"description":"Include each frame's locals and arguments."},
				    "maxFrames":        {"type":"integer","default":0,"description":"Max frames to return; 0 means all."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String sessionId = args.getString("sessionId");
		if (sessionId == null) {
			return McpToolResult.error("The 'sessionId' argument is required.");
		}
		ILaunch launch = DebugSessions.get(sessionId);
		if (launch == null) {
			return McpToolResult.error("No session '" + sessionId + "'. Use stm32_debug_control action=sessions to list them.");
		}
		if (!(launch instanceof GdbLaunch gdbLaunch)) {
			return McpToolResult.error("Session '" + sessionId + "' is not a DSF/GDB launch; frames need one.");
		}
		try {
			return McpToolResult.of(DebugSessions.framesDsf(gdbLaunch,
					args.getInt("thread", 0, 0, Integer.MAX_VALUE),
					args.getBoolean("includeVariables", true),
					args.getInt("maxFrames", 0, 0, 1000)).toString());
		} catch (CoreException e) {
			return McpToolResult.error("Reading the call stack failed: " + e.getMessage());
		}
	}
}
