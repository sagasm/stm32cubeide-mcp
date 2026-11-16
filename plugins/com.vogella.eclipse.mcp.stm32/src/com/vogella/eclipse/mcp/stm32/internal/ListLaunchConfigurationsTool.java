package com.vogella.eclipse.mcp.stm32.internal;

import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the debug launch configurations of the workspace, so a client can pick
 * the name to hand to the launch tool. Read only.
 */
public final class ListLaunchConfigurationsTool implements IMcpTool {

	/** The CDT launch attribute that names the project a configuration belongs to. */
	private static final String PROJECT_ATTR = "org.eclipse.cdt.launch.PROJECT_ATTR"; //$NON-NLS-1$

	private static final String ST_LAUNCH_TYPE = "com.st.stm32cube.ide.mcu.debug.launch.launchConfigurationType"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "stm32_list_launch_configurations"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Lists the debug launch configurations of this workspace with their type, modes, project and .launch file. Use it to pick the configuration name before launching a debug session on an ST target. The typeFilter narrows the list to a substring of the launch type id; the default keeps the ST MCU debug configurations."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "typeFilter": {"type":"string","default":"com.st.stm32cube.ide.mcu.debug.launch","description":"Substring a launch type id has to contain. Pass '.' to list every configuration in the workspace."},
				    "project":    {"type":"string","description":"Only configurations whose project attribute equals this name."},
				    "maxResults": {"type":"integer","default":50,"minimum":1,"maximum":500}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String typeFilter = args.getString("typeFilter", ST_LAUNCH_TYPE); //$NON-NLS-1$
		String project = args.getString("project"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 50, 1, 500);

		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		JsonArray configurations = new JsonArray();
		int reported = 0;
		int total = 0;
		try {
			for (ILaunchConfiguration configuration : manager.getLaunchConfigurations()) {
				String type = configuration.getType().getIdentifier();
				if (!type.toLowerCase().contains(typeFilter.toLowerCase())) {
					continue;
				}
				total++;
				if (reported >= maxResults) {
					continue;
				}
				String configurationProject = configuration.getAttribute(PROJECT_ATTR, ""); //$NON-NLS-1$
				if (project != null && !project.isBlank() && !project.equals(configurationProject)) {
					continue;
				}
				configurations.add(describe(configuration, type, configurationProject));
				reported++;
			}
		} catch (CoreException e) {
			return McpToolResult.error("Could not read the launch configurations: " + e.getMessage());
		}

		JsonObject result = new JsonObject()
				.put("total", total)
				.put("truncated", total > reported)
				.put("configurations", configurations);
		return McpToolResult.of(result.toString());
	}

	private static JsonObject describe(ILaunchConfiguration configuration, String type, String project)
			throws CoreException {
		JsonObject o = new JsonObject();
		o.put("name", configuration.getName());
		o.put("type", type);
		o.put("modes", String.join(",", configuration.getModes()));
		if (!project.isEmpty()) {
			o.put("project", project);
		}
		IFile file = configuration.getFile();
		if (file != null) {
			o.put("file", file.getFullPath().toString());
		}
		return o;
	}
}
