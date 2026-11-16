package com.vogella.eclipse.mcp.stm32.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports on and steers a debug session: its state and top stack frame, and
 * continue, pause, step, terminate. Every action answers with the state after
 * it ran, so a client does not need a second call to see the effect.
 */
public final class DebugControlTool implements IMcpTool {

	private static final List<String> ACTIONS = List.of("sessions", "status", "continue", "pause", "step_over",
			"step_into", "terminate");

	@Override
	public String getName() {
		return "stm32_debug_control"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Controls a debug session started by stm32_debug_launch. actions=sessions lists every session; status reports state, threads and the top stack frame with its source line, which is where a breakpoint landed; continue, pause, step_over, step_into and terminate steer the target and answer with the state after the action. When the target is suspended the top frame names the function and line the core stopped at."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["action"],
				  "properties": {
				    "action":    {"type":"string","enum":["sessions","status","continue","pause","step_over","step_into","terminate"]},
				    "sessionId": {"type":"string","description":"Required for every action but sessions."},
				    "thread":    {"type":"integer","default":0,"description":"Zero-based index of the thread to steer, for targets with more than one."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String action = args.getString("action", "sessions");
		if (!ACTIONS.contains(action)) {
			return McpToolResult.error("Unknown action '" + action + "', expected one of " + ACTIONS + ".");
		}

		if ("sessions".equals(action)) {
			JsonArray sessions = DebugSessions.describeAll();
			return McpToolResult.of(new JsonObject()
					.put("total", sessions.size())
					.put("sessions", sessions)
					.toString());
		}

		String sessionId = args.getString("sessionId");
		ILaunch launch = sessionId == null ? null : DebugSessions.get(sessionId);
		if (launch == null) {
			return McpToolResult.error("No session '" + sessionId + "'. Use action=sessions to list them.");
		}
		IDebugTarget target = launch.getDebugTarget();
		if (target == null && launch instanceof GdbLaunch gdbLaunch) {
			// DSF launches have no classic target; steer through the DSF services.
			try {
				if ("terminate".equals(action)) {
					if (gdbLaunch.canTerminate()) {
						gdbLaunch.terminate();
					}
				} else if (!"status".equals(action)) {
					DebugSessions.steerDsf(gdbLaunch, action, args.getInt("thread", 0, 0, Integer.MAX_VALUE));
				}
			} catch (CoreException e) {
				return McpToolResult.error("The '" + action + "' action failed: " + e.getMessage());
			}
			return McpToolResult.of(DebugSessions.describe(gdbLaunch, sessionId).toString());
		}
		if (target == null) {
			return McpToolResult.of(DebugSessions.describe(launch, sessionId).toString());
		}

		int threadIndex = args.getInt("thread", 0, 0, Integer.MAX_VALUE);
		try {
			run(action, target, threadIndex);
		} catch (CoreException e) {
			return McpToolResult.error("The '" + action + "' action failed: " + e.getMessage());
		}
		return McpToolResult.of(DebugSessions.describe(launch, sessionId).toString());
	}

	private static void run(String action, IDebugTarget target, int threadIndex) throws CoreException {
		switch (action) {
		case "terminate" -> {
			if (target.canTerminate()) {
				target.terminate();
			}
		}
		case "continue" -> threadOf(target, threadIndex).resume();
		case "pause" -> threadOf(target, threadIndex).suspend();
		case "step_over" -> threadOf(target, threadIndex).stepOver();
		case "step_into" -> threadOf(target, threadIndex).stepInto();
		default -> {
			// status needs no steering
		}
		}
	}

	private static IThread threadOf(IDebugTarget target, int threadIndex) throws CoreException {
		IThread[] threads = target.getThreads();
		if (threads.length == 0) {
			throw new CoreException(Status.error("The target has no threads to steer."));
		}
		if (threadIndex >= threads.length) {
			throw new CoreException(
					Status.error("Thread " + threadIndex + " does not exist, the target has " + threads.length + "."));
		}
		return threads[threadIndex];
	}
}
