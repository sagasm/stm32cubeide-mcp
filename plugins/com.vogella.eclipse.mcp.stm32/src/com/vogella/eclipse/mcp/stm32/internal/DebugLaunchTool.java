package com.vogella.eclipse.mcp.stm32.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Starts a debug session from a workspace launch configuration. The launch goes
 * through DebugUITools, the same path the IDE's Debug button uses, because the
 * ST delegate relies on the workbench for its build and probe steps; calling
 * the delegate directly from a background job leaves the session stuck in
 * launching. Because a debug launch builds, flashes the target and takes the
 * debug probe, it is dry-run by default.
 */
public final class DebugLaunchTool implements IMcpTool {

	@Override
	public String getName() {
		return "stm32_debug_launch"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Launches a workspace debug configuration on the target: mode debug builds the project, flashes the image through the configured probe and stops where the configuration says, exactly like the IDE Debug button. TAKES THE DEBUG PROBE: only one client of the probe can run at a time, and an already running session has to be terminated first. dryRun defaults to true and only reports what would be launched. The real run returns the session's id once the debug target exists, usually right after the flash; when it takes longer the answer says so and the session appears under the control tool's sessions action once it is up."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["configuration"],
				  "properties": {
				    "configuration": {"type":"string","description":"Launch configuration name, exactly as stm32_list_launch_configurations reports it, or a substring that matches exactly one of them."},
				    "mode":          {"type":"string","enum":["debug","run"],"default":"debug"},
				    "dryRun":        {"type":"boolean","default":true,"description":"true reports the plan without touching the target."},
				    "waitSeconds":   {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait for the debug target to exist before answering with what is known."}
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
		boolean dryRun = args.getBoolean("dryRun", true);
		int waitSeconds = args.getInt("waitSeconds", 25, 1, 3600);

		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		List<ILaunchConfiguration> matches = new ArrayList<>();
		try {
			for (ILaunchConfiguration candidate : manager.getLaunchConfigurations()) {
				if (candidate.getName().equals(name)) {
					matches.clear();
					matches.add(candidate);
					break;
				}
				if (candidate.getName().toLowerCase().contains(name.toLowerCase())) {
					matches.add(candidate);
				}
			}
		} catch (CoreException e) {
			return McpToolResult.error("Could not read the launch configurations: " + e.getMessage());
		}
		if (matches.isEmpty()) {
			return McpToolResult.error("No launch configuration matches '" + name + "'.");
		}
		if (matches.size() > 1) {
			JsonArray names = new JsonArray();
			for (ILaunchConfiguration candidate : matches) {
				names.add(candidate.getName());
			}
			return McpToolResult.error(new JsonObject()
					.put("error", "'" + name + "' matches several configurations, name one exactly.")
					.put("matches", names)
					.toString());
		}
		ILaunchConfiguration configuration = matches.get(0);

		JsonObject plan = new JsonObject()
				.put("configuration", configuration.getName())
				.put("mode", mode);
		try {
			plan.put("supportsMode", configuration.supportsMode(mode));
		} catch (CoreException e) {
			return McpToolResult.error("Could not read the configuration: " + e.getMessage());
		}

		if (dryRun) {
			plan.put("dryRun", true);
			plan.put("note", "Nothing was launched. Pass dryRun:false to build, flash and start this session; the debug probe is used exclusively while it runs, and a session that is already running has to be terminated first.");
			return McpToolResult.of(plan.toString());
		}

		// The J-Link reset strategy in the configuration runs 'monitor reset'
		// while finalising the launch. GDB rejects it when the target is still
		// running (a previous session that ended with 'continue' leaves it
		// running), which pops a modal error and stalls the launch. Halt the
		// target first with the J-Link Commander, which releases the probe on
		// exit, so the sequence always sees a halted target.
		JsonObject halted = haltTarget(configuration, plan);

		// DebugUITools.launch hands the delegate to the workbench and answers at
		// once, so the new session is recognised by comparing the launch set. The
		// IDE's own Debug button calls it on the UI thread and works, while the
		// same call from a background thread leaves the sequence dead before the
		// build, so this replays the button's thread exactly.
		// The CDT/ST final launch sequence (GROUP_JTAG) runs, in order:
		//   stepResetBoard          -> the reset strategy's 'monitor reset'
		//   stepUserInitCommands    -> org.eclipse...gdbjtag.core.initCommands
		//   stepLoadImage           -> downloads the ELF, leaving the target running
		//   stepStartTrackingBreakpoints -> inserts DEBUGGER_STOP_AT_MAIN's
		//                                   '-break-insert -f main'
		//   stepUserDebugCommands   -> org.eclipse...gdbjtag.core.runCommands
		// When the MCU was free-running before this call, load image leaves it
		// running and gdb refuses '-break-insert -f main' with "Cannot execute
		// this command while the target is running", which pops a MODAL error.
		// There is no user-command hook between load image and breakpoint
		// tracking, so a halt injected in initCommands is always too early and
		// one in runCommands is always too late to prevent it.
		//
		// Fix, all through a non-saved working copy (the .launch file on disk
		// is never touched): disable both automatic stop-at-main breakpoints so
		// neither '-break-insert -f main' (DEBUGGER_STOP_AT_MAIN, at
		// stepStartTrackingBreakpoints) nor '-break-insert -t -f main'
		// (setStopAt, at stepStopScript) is emitted while the target runs.
		// Re-create the breakpoint at stepUserDebugCommands with the plain CLI
		// 'break <symbol>' (no -f): gdb handles a running target itself without
		// popping the modal, and its internal thread-group state stays valid.
		// The sequence's stepResumeScript then resumes into the symbol.
		// Do NOT use J-Link's 'monitor halt' here: it halts the probe behind
		// gdb's back, leaving gdb's thread-group bookkeeping inconsistent and
		// breaking later steer/continue. initCommands keeps its early halt so
		// the reset strategy's 'monitor reset' stays happy.
		ILaunchConfiguration configurationToLaunch = configuration;
		try {
			ILaunchConfigurationWorkingCopy workingCopy = configuration.getWorkingCopy();
			String initCommands = workingCopy.getAttribute("org.eclipse.cdt.debug.gdbjtag.core.initCommands", "");
			if (!initCommands.contains("monitor halt")) {
				workingCopy.setAttribute("org.eclipse.cdt.debug.gdbjtag.core.initCommands",
						"monitor halt\n" + initCommands);
			}
			if (workingCopy.getAttribute("org.eclipse.cdt.launch.DEBUGGER_STOP_AT_MAIN", false)) {
				String symbol = workingCopy.getAttribute(
						"org.eclipse.cdt.launch.DEBUGGER_STOP_AT_MAIN_SYMBOL", "main");
				if (symbol == null || symbol.isBlank()) {
					symbol = "main";
				}
				workingCopy.setAttribute("org.eclipse.cdt.launch.DEBUGGER_STOP_AT_MAIN", false);
				workingCopy.setAttribute("org.eclipse.cdt.debug.gdbjtag.core.setStopAt", false);
				String runCommands = workingCopy.getAttribute("org.eclipse.cdt.debug.gdbjtag.core.runCommands", "");
				String breakCommand = "break " + symbol;
				if (!runCommands.contains(breakCommand)) {
					workingCopy.setAttribute("org.eclipse.cdt.debug.gdbjtag.core.runCommands",
							breakCommand + "\n" + runCommands);
				}
			}
			configurationToLaunch = workingCopy;
		} catch (CoreException e) {
			// Fall back to the saved configuration; the pre-launch halt above
			// still covers the common case.
		}
		final ILaunchConfiguration launchConfiguration = configurationToLaunch;

		Set<ILaunch> before = new HashSet<>(Arrays.asList(manager.getLaunches()));
		org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			DebugUITools.launch(launchConfiguration, mode);
		});

		int bounded = CallBudget.boundedWaitSeconds(waitSeconds);
		long deadline = System.currentTimeMillis() + bounded * 1000L;
		ILaunch found = null;
		boolean targetUp = false;
		while (System.currentTimeMillis() < deadline) {
			for (ILaunch launch : manager.getLaunches()) {
				if (!before.contains(launch)) {
					found = launch;
					if (launch.getDebugTarget() != null) {
						targetUp = true;
						break;
					}
				}
			}
			if (targetUp) {
				break;
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		if (found == null) {
			plan.put("dryRun", false);
			plan.put("state", "launching");
			plan.put("waitedSeconds", bounded);
			plan.put("note", "Still building or flashing. Poll stm32_debug_control with action=sessions; the session is listed as soon as the launch exists.");
			return McpToolResult.of(plan.toString());
		}
		String sessionId = DebugSessions.register(found);
		JsonObject result = DebugSessions.describe(found, sessionId);
		result.put("waitedSeconds", bounded);
		if (halted != null) {
			result.put("preLaunchHalt", halted);
		}
		return McpToolResult.of(result.toString());
	}

	/**
	 * Halts the target through the J-Link Commander before the launch so that
	 * the configuration's 'monitor reset' is never rejected with "target is
	 * running". Uses the device/interface/speed recorded in the launch
	 * configuration, and never fails the launch: an unavailable Commander or
	 * an occupied probe is reported in the answer and the normal sequence goes
	 * ahead. The Commander process exits before DebugUITools.launch runs, so
	 * the probe is free again for the GDB server.
	 */
	private static JsonObject haltTarget(ILaunchConfiguration configuration, JsonObject plan) {
		JsonObject report = new JsonObject();
		String device = null;
		String iface = "SWD";
		String speed = "4000";
		try {
			device = configuration.getAttribute("com.st.stm32cube.ide.mcu.debug.jlink.device", (String) null);
			if (!configuration.getAttribute("com.st.stm32cube.ide.mcu.debug.launch.swd_mode", true)) {
				iface = "JTAG";
			}
			String configuredSpeed = configuration
					.getAttribute("com.st.stm32cube.ide.mcu.debug.jlink.init_speed", (String) null);
			if (configuredSpeed != null && !configuredSpeed.isBlank()) {
				speed = configuredSpeed;
			}
		} catch (CoreException e) {
			report.put("status", "skipped: could not read J-Link settings: " + e.getMessage());
			return report;
		}
		if (device == null || device.isBlank()) {
			report.put("status", "skipped: no J-Link device in the configuration");
			return report;
		}
		report.put("device", device).put("interface", iface).put("speedKHz", speed);

		String commander = findCommander();
		if (commander == null) {
			report.put("status", "skipped: J-Link Commander not found");
			return report;
		}

		java.io.File script = null;
		try {
			script = java.io.File.createTempFile("mcp-jlink-halt", ".jlink");
			String commands = "si " + iface + "\nspeed " + speed + "\ndevice " + device
					+ "\nconnect\nhalt\nexit\n";
			java.nio.file.Files.writeString(script.toPath(), commands);
			ProcessBuilder builder = new ProcessBuilder(commander, "-CommanderScript",
					script.getAbsolutePath(), "-NoGui", "1");
			builder.redirectErrorStream(true);
			Process process = builder.start();
			String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				report.put("status", "timeout (probe may be busy)");
				return report;
			}
			boolean halted = output.contains("J-Link>halt") && output.contains("PC = ");
			report.put("status", halted ? "ok" : "not confirmed");
			report.put("exitCode", process.exitValue());
			if (!halted) {
				report.put("output", output.substring(0, Math.min(400, output.length())));
			}
		} catch (Exception e) {
			report.put("status", "skipped: " + e.getMessage());
		} finally {
			if (script != null) {
				script.delete();
			}
		}
		return report;
	}

	/** Locates JLink.exe in the CubeIDE J-Link external-tools bundle. */
	private static String findCommander() {
		String install = System.getProperty("osgi.install.area");
		if (install != null && install.startsWith("file:")) {
			try {
				java.nio.file.Path root = java.nio.file.Paths.get(new java.net.URI(install));
				try (java.util.stream.Stream<java.nio.file.Path> dirs = java.nio.file.Files.list(root.resolve("plugins"))) {
					for (java.nio.file.Path dir : dirs.filter(java.nio.file.Files::isDirectory)
							.filter(p -> p.getFileName().toString().startsWith(
									"com.st.stm32cube.ide.mcu.externaltools.jlink.win32"))
							.toList()) {
						java.nio.file.Path exe = dir.resolve("tools").resolve("bin").resolve("JLink.exe");
						if (java.nio.file.Files.isRegularFile(exe)) {
							return exe.toString();
						}
					}
				}
			} catch (Exception e) {
				return null;
			}
		}
		return null;
	}
}
