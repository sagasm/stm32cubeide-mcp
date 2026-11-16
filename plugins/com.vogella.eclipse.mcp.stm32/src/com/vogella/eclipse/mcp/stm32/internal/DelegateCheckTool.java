package com.vogella.eclipse.mcp.stm32.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.LaunchConfigurationDelegate;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Runs the launch delegate's pre-launch steps step by step, outside the opaque
 * DebugUITools job. When a launch stays in "launching" forever this shows which
 * step stops it and why, because a background job's error is otherwise only
 * visible in the Progress view. It never touches the debug probe: it stops
 * before the launch itself.
 */
public final class DelegateCheckTool implements IMcpTool {

	@Override
	public String getName() {
		return "stm32_debug_delegate_check"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Diagnostic for a debug configuration whose launch stays stuck in 'launching': it calls the launch delegate's own steps one by one - getLaunch, preLaunchCheck, buildForLaunch (this REALLY builds the project, output appears in the Console view) and finalLaunchCheck - and reports each step's result or full exception stack. It stops before the launch itself, so the debug probe is not taken."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["configuration"],
				  "properties": {
				    "configuration": {"type":"string","description":"Launch configuration name, exactly as stm32_list_launch_configurations reports it."},
				    "mode":          {"type":"string","enum":["debug","run"],"default":"debug"},
				    "withLaunch":    {"type":"boolean","default":false,"description":"After the pre-launch steps pass, also call the delegate's launch itself, TAKING THE DEBUG PROBE. Its errors are reported by a later call of this tool; the running session shows up under stm32_debug_control sessions."},
				    "waitSeconds":   {"type":"integer","default":25,"minimum":1,"maximum":3600}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String name = args.getString("configuration");
		if (name == null || name.isBlank()) {
			return McpToolResult.error("Give the 'configuration' name from stm32_list_launch_configurations.");
		}
		String mode = "run".equals(args.getString("mode", "debug")) ? ILaunchManager.RUN_MODE
				: ILaunchManager.DEBUG_MODE;

		ILaunchConfiguration configuration;
		try {
			configuration = findExactlyOne(name, DebugPlugin.getDefault().getLaunchManager());
		} catch (CoreException e) {
			return McpToolResult.error(e.getMessage());
		}

		boolean withLaunch = args.getBoolean("withLaunch", false);
		DelegateRunner runner = new DelegateRunner(configuration, mode, withLaunch);
		Job check = Job.create("MCP delegate check " + configuration.getName(), monitor2 -> {
			runner.run(monitor2);
			return Status.OK_STATUS;
		});
		check.schedule();
		int bounded = CallBudget.boundedWaitSeconds(args.getInt("waitSeconds", 25, 1, 3600));
		long deadline = System.currentTimeMillis() + bounded * 1000L;
		while (System.currentTimeMillis() < deadline && check.getState() != Job.NONE) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		JsonObject result = new JsonObject()
				.put("configuration", configuration.getName())
				.put("mode", mode)
				.put("completed", check.getState() == Job.NONE)
				.put("steps", runner.steps);
		return McpToolResult.of(result.toString());
	}

	private static ILaunchConfiguration findExactlyOne(String name, ILaunchManager manager) throws CoreException {
		ILaunchConfiguration match = null;
		int count = 0;
		for (ILaunchConfiguration candidate : manager.getLaunchConfigurations()) {
			if (candidate.getName().equals(name)) {
				return candidate;
			}
			if (candidate.getName().toLowerCase().contains(name.toLowerCase())) {
				match = candidate;
				count++;
			}
		}
		if (count == 1) {
			return match;
		}
		throw new CoreException(Status.error(
				count == 0 ? "No launch configuration matches '" + name + "'."
						: "'" + name + "' matches several configurations, name one exactly."));
	}

	/** Step-by-step delegate execution, collecting one entry per step. */
	private static final class DelegateRunner {

		private final ILaunchConfiguration configuration;

		private final String mode;

		private final boolean withLaunch;

		private final JsonArray steps = new JsonArray();

		DelegateRunner(ILaunchConfiguration configuration, String mode, boolean withLaunch) {
			this.configuration = configuration;
			this.mode = mode;
			this.withLaunch = withLaunch;
		}

		void run(IProgressMonitor monitor) {
			try {
				org.eclipse.debug.core.model.ILaunchConfigurationDelegate delegate =
						configuration.getType().getDelegate(mode);
				step("delegate", () -> delegate.getClass().getName());
				if (!(delegate instanceof LaunchConfigurationDelegate base)) {
					step("note", () -> "The delegate does not extend LaunchConfigurationDelegate; step checks are unavailable.");
					return;
				}
				step("getLaunch", () -> String.valueOf(base.getLaunch(configuration, mode) != null));
				step("preLaunchCheck", () -> String.valueOf(base.preLaunchCheck(configuration, mode, monitor)));
				step("buildForLaunch", () -> String.valueOf(base.buildForLaunch(configuration, mode, monitor)));
				step("finalLaunchCheck", () -> String.valueOf(base.finalLaunchCheck(configuration, mode, monitor)));
				if (!withLaunch) {
					step("done", () -> "All pre-launch steps returned true. The launch itself was NOT started; the probe was not taken."
							+ (lastLaunchError == null ? "" : " LAST DETACHED LAUNCH ERROR: " + lastLaunchError));
					return;
				}
				// The launch takes minutes (build, flash, gdb), longer than one MCP
				// call may stay open, so it runs detached and reports asynchronously.
				ILaunch preCreated = base.getLaunch(configuration, mode);
				if (preCreated != null) {
					// ILaunchConfiguration.launch() registers the launch with the
					// manager itself; calling the delegate directly skips that step,
					// and without it the session never shows up anywhere.
					DebugPlugin.getDefault().getLaunchManager().addLaunch(preCreated);
				}
				Job launchJob = Job.create("MCP delegate launch " + configuration.getName(), m -> {
					try {
						base.launch(configuration, mode, preCreated, m);
					} catch (Exception e) {
						lastLaunchError = stackOf(e);
					}
					return Status.OK_STATUS;
				});
				launchJob.schedule();
				step("delegate.launch", () -> "Started in the background (job " + launchJob.getName()
						+ "). Poll stm32_debug_control with action=sessions for the target, and call this tool again to read any launch error: "
						+ (lastLaunchError == null ? "(no error recorded yet)" : lastLaunchError));
			} catch (CoreException e) {
				step("exception", () -> stackOf(e));
			}
		}

		/** The stack of the last detached delegate.launch failure, if any. */
		private static volatile String lastLaunchError;

		private void step(String name, java.util.concurrent.Callable<String> action) {
			JsonObject o = new JsonObject();
			o.put("step", name);
			try {
				o.put("result", action.call());
			} catch (Exception e) {
				o.put("failed", true);
				o.put("stack", stackOf(e));
			}
			steps.add(o);
		}

		private static String stackOf(Throwable t) {
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			return sw.toString();
		}
	}
}
