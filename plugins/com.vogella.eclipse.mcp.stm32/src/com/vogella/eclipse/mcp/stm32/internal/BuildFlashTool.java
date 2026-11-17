package com.vogella.eclipse.mcp.stm32.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Starts (or re-starts) an ST debug launch, which is what builds and flashes
 * the target in CubeIDE. The tool delegates the launch entirely to the IDE's
 * own DebugUITools so the built-in terminate-and-relaunch flow is reused; it
 * only adds a dry run, an operation handle and a usable session id.
 */
public final class BuildFlashTool implements IMcpTool {

	@Override
	public String getName() {
		return "stm32_build_and_flash"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Build and flash an STM32 project by starting one of the ST launch configurations listed by stm32_list_launch_configurations (e.g. 'kz036 Debug'). REQUIRED: launchConfiguration is the launch configuration's name and must always be supplied - call stm32_list_launch_configurations first to obtain it; project and configuration are optional. Omitting launchConfiguration fails schema validation. Starting the debug session is what builds the saved resources and downloads the ELF to Flash - unsaved editor buffers are NOT built. THIS WRITES THE TARGET FLASH and holds the debug probe until the session is terminated. If a session for this configuration is already active it is terminated and waited for to finish before the new launch starts, replaying the IDE's Terminate-and-Relaunch button without the modal error dialog. Where it stops afterwards is decided by the launch configuration, exactly as when you click Debug in the IDE. dryRun defaults to true and only returns the plan. The real call returns an operationId immediately; poll stm32_flash_status until it is done, then use the sessionId there with the other stm32_debug_* tools."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["launchConfiguration"],
				  "properties": {
				    "launchConfiguration": {"type":"string","description":"Name of an ST launch configuration from stm32_list_launch_configurations."},
				    "project":              {"type":"string","description":"Optional project name; when given it must match the configuration's project attribute."},
				    "configuration":        {"type":"string","description":"Optional build configuration name, reported in the plan only."},
				    "dryRun":               {"type":"boolean","default":true,"description":"Return the plan without building or flashing."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String name = args.getString("launchConfiguration");
		if (name == null) {
			return McpToolResult.error("The 'launchConfiguration' argument is required.");
		}
		boolean dryRun = args.getBoolean("dryRun", true);

		ILaunchConfiguration configuration;
		try {
			configuration = findConfiguration(name);
		} catch (Exception e) {
			return McpToolResult.error("Could not read launch configurations: " + e.getMessage());
		}
		if (configuration == null) {
			return McpToolResult.error("No launch configuration matches '" + name + "'.");
		}

		String project = args.getString("project");
		JsonObject plan = new JsonObject()
				.put("launchConfiguration", configuration.getName())
				.put("mode", ILaunchManager.DEBUG_MODE);
		String resolvedProject = project;
		try {
			String configProject = configuration.getAttribute("org.eclipse.cdt.launch.PROJECT_ATTR", "");
			resolvedProject = project == null ? configProject : project;
			plan.put("project", resolvedProject);
			plan.put("program", configuration.getAttribute("org.eclipse.cdt.launch.PROGRAM_NAME", ""));
			plan.put("performBuild", configuration.getAttribute(
					"org.eclipse.cdt.launch.ATTR_BUILD_BEFORE_LAUNCH_ATTR", 0) != 0);
		} catch (Exception e) {
			return McpToolResult.error("Could not read the configuration: " + e.getMessage());
		}
		String requestedConfig = args.getString("configuration");
		if (requestedConfig != null) {
			plan.put("configuration", requestedConfig);
		}

		if (dryRun) {
			boolean alreadyRunning = !activeLaunches(configuration).isEmpty();
			plan.put("dryRun", true);
			plan.put("alreadyActive", alreadyRunning);
			plan.put("note", "Nothing was built or flashed. Pass dryRun:false to "
					+ (alreadyRunning ? "terminate-and-relaunch this configuration, or " : "start a new debug session and ")
					+ "build the saved resources and download the ELF."); //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.of(plan.toString());
		}

		FlashRegistry.Flash flash = FlashRegistry.getInstance()
				.start(configuration.getName(), resolvedProject);
		startLaunchJob(configuration, flash);
		plan.put("dryRun", false);
		plan.put("operationId", flash.id());
		plan.put("state", "running"); //$NON-NLS-1$
		plan.put("note", "Build and flash started. Poll stm32_flash_status with this operationId; the probe is in use until the session ends."); //$NON-NLS-1$
		return McpToolResult.of(plan.toString());
	}

	private static ILaunchConfiguration findConfiguration(String name) throws Exception {
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfiguration exact = null;
		List<ILaunchConfiguration> matches = new ArrayList<>();
		for (ILaunchConfiguration candidate : manager.getLaunchConfigurations()) {
			if (name.equals(candidate.getName())) {
				exact = candidate;
				break;
			}
			if (candidate.getName().toLowerCase().contains(name.toLowerCase())) {
				matches.add(candidate);
			}
		}
		if (exact != null) {
			return exact;
		}
		return matches.size() == 1 ? matches.get(0) : null;
	}

	/** Active launches of the same configuration, which must be terminated first. */
	private static List<ILaunch> activeLaunches(ILaunchConfiguration configuration) {
		List<ILaunch> active = new ArrayList<>();
		for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
			if (!launch.isTerminated() && configuration.equals(launch.getLaunchConfiguration())) {
				active.add(launch);
			}
		}
		return active;
	}

	/** Waits until every launch has really terminated (IDE button's postExecute). */
	private static boolean awaitTerminated(List<ILaunch> launches, int seconds) {
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			boolean allTerminated = true;
			for (ILaunch launch : launches) {
				if (!launch.isTerminated()) {
					allTerminated = false;
					break;
				}
			}
			if (allTerminated) {
				return true;
			}
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	private static void startLaunchJob(ILaunchConfiguration configuration, FlashRegistry.Flash flash) {
		Job job = new Job("MCP build and flash " + configuration.getName()) { //$NON-NLS-1$
			@Override
			protected org.eclipse.core.runtime.IStatus run(IProgressMonitor jobMonitor) {
				try {
					removeTerminatedLaunches();
					ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
					// Replay the IDE's Terminate-and-Relaunch button exactly:
					// terminate the active launch, WAIT for it to really finish
					// (the button uses the IRequest postExecute callback), then
					// start a fresh launch. DebugUITools.reLaunch does not wait
					// and pops "Termination of previous launch did not complete
					// successfully" when the old ST launch is still winding down.
					List<ILaunch> active = activeLaunches(configuration);
					for (ILaunch old : active) {
						if (old.canTerminate()) {
							old.terminate();
						}
					}
					if (!active.isEmpty() && !awaitTerminated(active, 45)) {
						flash.fail("The previous launch did not terminate within 45 s; nothing was started."); //$NON-NLS-1$
						return org.eclipse.core.runtime.Status.OK_STATUS;
					}
					Set<ILaunch> before = new HashSet<>(Arrays.asList(manager.getLaunches()));
					PlatformUI.getWorkbench().getDisplay()
							.asyncExec(() -> DebugUITools.launch(configuration, ILaunchManager.DEBUG_MODE));
					ILaunch found = awaitLaunch(manager, before, 90);
					if (found == null) {
						flash.fail("No debug launch appeared within 90 s; the build or download may have failed."); //$NON-NLS-1$
						return org.eclipse.core.runtime.Status.OK_STATUS;
					}
					String sessionId = DebugSessions.register(found);
					flash.done(sessionId, "Debug session " + configuration.getName() + " started; ELF downloaded by the launch."); //$NON-NLS-1$ //$NON-NLS-2$
				} catch (Exception e) {
					flash.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
				}
				return org.eclipse.core.runtime.Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.schedule();
	}

	/** Equivalent of the Debug view's "Remove All Terminated Launches" action. */
	private static void removeTerminatedLaunches() {
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		List<ILaunch> terminated = Arrays.stream(manager.getLaunches()).filter(ILaunch::isTerminated).toList();
		if (!terminated.isEmpty()) {
			manager.removeLaunches(terminated.toArray(new ILaunch[0]));
		}
	}

	private static ILaunch awaitLaunch(ILaunchManager manager, Set<ILaunch> before, int seconds) {
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		ILaunch fallback = null;
		while (System.currentTimeMillis() < deadline) {
			for (ILaunch launch : manager.getLaunches()) {
				if (before.contains(launch)) {
					continue;
				}
				// Prefer the launch whose DSF services are actually ready: ST
				// creates an outer launching shell first, and getSession().isActive()
				// becomes true before the services the debug tools need exist.
				// Use the same service set as DebugSessions.describeDsf.
				if (launch instanceof org.eclipse.cdt.dsf.gdb.launching.GdbLaunch gdb
						&& DebugSessions.isDsfReady(gdb)) {
					return launch;
				}
				if (fallback == null) {
					fallback = launch;
				}
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return fallback;
			}
		}
		return fallback;
	}
}
