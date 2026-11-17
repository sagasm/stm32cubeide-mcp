package com.vogella.eclipse.mcp.stm32.internal;

import java.util.ArrayList;
import java.util.List;
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
		return "Lists the debug launch configurations of this workspace with their type, modes, project and .launch file. Use it to pick the configuration name before launching a debug session on an ST target. The typeFilter narrows the list to a substring of the launch type id; the default keeps the ST MCU debug configurations; project filters by the configuration's project. total counts the configurations that passed both filters, returned is how many are in the array, and truncated is true only when maxResults cut that filtered set short; totalBeforeFilter appears when the project filter removed some."; //$NON-NLS-1$
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
		List<JsonObject> all = new ArrayList<>();
		int totalBeforeFilter = 0;
		try {
			for (ILaunchConfiguration configuration : manager.getLaunchConfigurations()) {
				String type = configuration.getType().getIdentifier();
				if (!type.toLowerCase().contains(typeFilter.toLowerCase())) {
					continue;
				}
				totalBeforeFilter++;
				String configurationProject = configuration.getAttribute(PROJECT_ATTR, ""); //$NON-NLS-1$
				if (project != null && !project.isBlank() && !project.equals(configurationProject)) {
					continue;
				}
				all.add(describe(configuration, type, configurationProject));
			}
		} catch (CoreException e) {
			return McpToolResult.error("Could not read the launch configurations: " + e.getMessage());
		}

		int total = all.size();
		int returned = Math.min(total, maxResults);
		JsonArray configurations = new JsonArray();
		for (int i = 0; i < returned; i++) {
			configurations.add(all.get(i));
		}
		JsonObject result = new JsonObject()
				.put("total", Integer.valueOf(total))
				.put("returned", Integer.valueOf(returned))
				.put("truncated", Boolean.valueOf(total > returned))
				.put("configurations", configurations);
		if (totalBeforeFilter != total) {
			result.put("totalBeforeFilter", Integer.valueOf(totalBeforeFilter));
		}
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
