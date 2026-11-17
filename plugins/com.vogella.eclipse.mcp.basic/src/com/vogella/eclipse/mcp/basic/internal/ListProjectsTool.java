package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the projects of the workspace.
 */
public final class ListProjectsTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_list_projects"; //$NON-NLS-1$
	}

	/** .project is machine written, so the element is enough and an XML parse is not. */
	private static final Pattern NATURE = Pattern.compile("<nature>([^<]*)</nature>"); //$NON-NLS-1$

	@Override
	public String getDescription() {
		return "Lists the projects in the Eclipse workspace, with their natures and open/closed state. A closed project still reports its natures, read from its .project file on disk, because the Java model cannot answer for a project it has not opened. natureSource says which of the two answered, so 'has no natures' is never confused with 'could not be asked'. natureFilter narrows to projects declaring one of the given natures (a string or an array), openOnly drops closed projects, and namePattern is a '*'/'?' glob over project names; all three apply before the counts, so the answer describes the selection, and workspaceTotal still gives the unfiltered size. Pass a stateMarker from eclipse_set_project_state to add stateChanges: which projects opened, closed, appeared or disappeared since that call, with their before/after states, so the client does not have to snapshot the workspace itself. Pass summary true for counts only (total, open, closed and projects per nature), which is the cheap answer to how big a workspace is; a platform workspace lists several hundred projects."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "maxResults":   {"type":"integer","default":200,"minimum":1,"maximum":5000},
				    "summary":      {"type":"boolean","default":false,"description":"Answer with counts only and no per-project entries."},
				    "natureFilter": {"oneOf":[{"type":"string"},{"type":"array","items":{"type":"string"}}],"description":"Only projects declaring one of these natures, e.g. 'org.eclipse.jdt.core.javanature'. A string or an array. Closed projects are matched from .project on disk."},
				    "openOnly":     {"type":"boolean","default":false,"description":"Only open projects."},
				    "namePattern":  {"type":"string","description":"Glob over project names, '*' and '?' allowed, case insensitive."},
				    "stateMarker":  {"type":"string","description":"A marker from eclipse_set_project_state. Adds stateChanges: projects whose open/closed state changed since that call, with before/after states."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		int maxResults = args.getInt("maxResults", 200, 1, 5000); //$NON-NLS-1$
		boolean summary = args.getBoolean("summary", false); //$NON-NLS-1$
		boolean openOnly = args.getBoolean("openOnly", false); //$NON-NLS-1$
		List<String> natureFilter = stringList(arguments, "natureFilter"); //$NON-NLS-1$
		Pattern namePattern;
		try {
			namePattern = Globs.compile(args.getString("namePattern")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'namePattern' as a glob: " + e.getMessage()); //$NON-NLS-1$
		}
		String stateMarker = args.getString("stateMarker"); //$NON-NLS-1$
		Map<String, String> baseline = null;
		if (stateMarker != null) {
			baseline = ProjectStateBaselines.of(stateMarker);
			if (baseline == null) {
				return McpToolResult.error("'%s' is not a state marker this server still holds. Take one with eclipse_set_project_state; only the last few are kept. Known: %s" //$NON-NLS-1$
						.formatted(stateMarker, ProjectStateBaselines.ids()));
			}
		}

		List<Entry> entries = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			boolean open = project.isOpen();
			List<String> natures;
			String natureSource;
			if (open) {
				natureSource = "model"; //$NON-NLS-1$
				try {
					natures = List.of(project.getDescription().getNatureIds());
				} catch (CoreException e) {
					throw new McpToolException("Could not read the natures of project " + project.getName(), e);
				}
			} else {
				List<String> onDisk = naturesFromProjectFile(project);
				natures = onDisk == null ? List.of() : onDisk;
				natureSource = onDisk == null ? "unknown" : "projectFile"; //$NON-NLS-1$ //$NON-NLS-2$
			}
			IPath location = project.getLocation();
			entries.add(new Entry(project.getName(), open, natures, natureSource,
					location == null ? null : location.toOSString()));
		}
		int workspaceTotal = entries.size();
		List<Entry> selected = entries.stream()
				.filter(entry -> !openOnly || entry.open())
				.filter(entry -> namePattern == null || namePattern.matcher(entry.name()).matches())
				.filter(entry -> natureFilter.isEmpty()
						|| entry.natures().stream().anyMatch(natureFilter::contains))
				.toList();

		JsonArray stateChanges = new JsonArray();
		int stateChanged = 0;
		if (baseline != null) {
			Set<String> names = new LinkedHashSet<>();
			for (Entry entry : entries) {
				names.add(entry.name());
				String was = baseline.get(entry.name());
				String now = entry.open() ? "open" : "closed"; //$NON-NLS-1$ //$NON-NLS-2$
				if (was == null || !was.equals(now)) {
					stateChanged++;
					stateChanges.add(new JsonObject().put("name", entry.name()) //$NON-NLS-1$
							.put("from", was).put("to", now)); //$NON-NLS-1$
				}
			}
			for (Map.Entry<String, String> gone : baseline.entrySet()) {
				if (!names.contains(gone.getKey())) {
					stateChanged++;
					stateChanges.add(new JsonObject().put("name", gone.getKey()) //$NON-NLS-1$
							.put("from", gone.getValue()).put("to", null)); //$NON-NLS-1$
				}
			}
		}

		int open = 0;
		Map<String, Integer> byNature = new TreeMap<>();
		for (Entry entry : selected) {
			if (entry.open()) {
				open++;
			}
			entry.natures().forEach(nature -> byNature.merge(nature, Integer.valueOf(1), Integer::sum));
		}
		JsonObject natures = new JsonObject();
		byNature.forEach(natures::put);
		JsonObject result = new JsonObject().put("total", Integer.valueOf(selected.size())) //$NON-NLS-1$
				.put("workspaceTotal", Integer.valueOf(workspaceTotal)) //$NON-NLS-1$
				.put("open", Integer.valueOf(open)) //$NON-NLS-1$
				.put("closed", Integer.valueOf(selected.size() - open)) //$NON-NLS-1$
				.put("byNature", natures); //$NON-NLS-1$
		if (stateMarker != null) {
			result.put("stateMarker", stateMarker) //$NON-NLS-1$
					.put("sinceStateMarker", Boolean.TRUE) //$NON-NLS-1$
					.put("stateChangedCount", Integer.valueOf(stateChanged)); //$NON-NLS-1$
		}
		if (summary) {
			return McpToolResult.of(result.toString());
		}
		JsonArray projects = new JsonArray();
		for (Entry entry : selected.subList(0, Math.min(maxResults, selected.size()))) {
			JsonArray declared = new JsonArray();
			entry.natures().forEach(declared::add);
			projects.add(new JsonObject().put("name", entry.name()) //$NON-NLS-1$
					.put("open", entry.open()) //$NON-NLS-1$
					.put("natures", declared) //$NON-NLS-1$
					.put("natureSource", entry.natureSource()) //$NON-NLS-1$
					.put("location", entry.location())); //$NON-NLS-1$
		}
		result.put("truncated", Boolean.valueOf(selected.size() > projects.size())) //$NON-NLS-1$
				.put("projects", projects); //$NON-NLS-1$
		if (stateMarker != null) {
			result.put("stateChanges", stateChanges); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}

	private record Entry(String name, boolean open, List<String> natures, String natureSource, String location) {
	}

	private static List<String> stringList(Map<String, Object> arguments, String name) {
		Object raw = arguments == null ? null : arguments.get(name);
		if (raw instanceof String single) {
			return single.isBlank() ? List.of() : List.of(single.strip());
		}
		if (raw instanceof List<?> list) {
			return list.stream().map(String::valueOf).map(String::strip).filter(value -> !value.isEmpty()).toList();
		}
		return List.of();
	}

	/**
	 * The natures a closed project declares, or {@code null} when the file cannot be
	 * read.
	 * <p>
	 * {@code IProject.getDescription} fails on a closed project, and reporting "no natures" would be wrong.
	 */
	private static List<String> naturesFromProjectFile(IProject project) {
		IPath location = project.getLocation();
		if (location == null) {
			return null;
		}
		String content;
		try {
			content = Files.readString(location.append(".project").toFile().toPath()); //$NON-NLS-1$
		} catch (IOException | RuntimeException e) {
			return null;
		}
		List<String> natures = new ArrayList<>();
		Matcher matcher = NATURE.matcher(content);
		while (matcher.find()) {
			natures.add(matcher.group(1).trim());
		}
		return natures;
	}
}
