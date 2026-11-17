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
 * Evaluates a C/ST expression in the top frame of a suspended debug session,
 * via GDB/MI -data-evaluate-expression. Works for globals and, while stopped
 * in a frame, for locals of that frame.
 */
public final class EvaluateTool implements IMcpTool {

	@Override
	public String getName() {
		return "stm32_debug_evaluate"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Evaluate a C/ST expression (variable, arithmetic, cast, function call) in a debug session started by stm32_debug_launch. The session must be suspended at a breakpoint; globals and locals of the current frame can be read. By default the top (innermost) frame is used. frameId selects a different stack frame by its zero-based level, the same 'level' stm32_debug_frames reports: 0 is the top frame, 1 its caller, and so on. Locals resolve against the selected frame, so a local visible only in a caller can be read there. Returns the value as GDB prints it, plus the frame (function/file/line) it was evaluated in and its level."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["sessionId", "expression"],
				  "properties": {
				    "sessionId":  {"type":"string","description":"Session id from stm32_debug_launch or stm32_debug_control action=sessions."},
				    "expression": {"type":"string","description":"C/ST expression to evaluate, e.g. 'counter', 'x + 1', '(int)flag'."},
				    "thread":     {"type":"integer","default":0,"description":"Zero-based thread index whose frame hosts the evaluation."},
				    "frameId":    {"type":"integer","minimum":0,"description":"Zero-based stack frame level to evaluate in, as reported by stm32_debug_frames; 0 is the top (innermost) frame. Omit to use the top frame. Locals resolve against this frame."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String sessionId = args.getString("sessionId");
		String expression = args.getString("expression");
		if (sessionId == null) {
			return McpToolResult.error("The 'sessionId' argument is required.");
		}
		if (expression == null || expression.isBlank()) {
			return McpToolResult.error("The 'expression' argument must not be empty.");
		}
		ILaunch launch = DebugSessions.get(sessionId);
		if (launch == null) {
			return McpToolResult.error("No session '" + sessionId + "'. Use stm32_debug_control action=sessions to list them.");
		}
		if (!(launch instanceof GdbLaunch gdbLaunch)) {
			return McpToolResult.error("Session '" + sessionId + "' is not a DSF/GDB launch; evaluation needs one.");
		}
		Integer frameId = args.has("frameId")
				? Integer.valueOf(args.getInt("frameId", 0, 0, Integer.MAX_VALUE))
				: null;
		try {
			return McpToolResult.of(DebugSessions
					.evaluateDsf(gdbLaunch, expression, args.getInt("thread", 0, 0, Integer.MAX_VALUE), frameId)
					.toString());
		} catch (CoreException e) {
			return McpToolResult.error("Evaluating '" + expression + "' failed: " + e.getMessage());
		}
	}
}
