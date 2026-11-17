package com.vogella.eclipse.mcp.basic.internal;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Maps between project names, workspace paths, locations on disk and the
 * repository a file belongs to.
 */
public final class ResolvePathTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_resolve_path"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Answers where something is: given project names, workspace paths or absolute paths in any mix, it reports for each the project, the workspace path, the location on disk, the repository root and the path inside that repository. Changes nothing. This is the mapping a command line cannot work out and the IDE already holds, and it is what makes git usable from outside: 'org.eclipse.compare' becomes a repository root to run in and a path to pass after --, so git log, blame and log -L can be composed directly instead of being guessed at. It resolves in both directions, so an absolute path coming back from a build or a stack trace turns into the project it belongs to. Nothing here needs EGit; the repository is found by walking up for .git, which also resolves a linked worktree."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["of"],
				  "properties": {
				    "of": {
				      "oneOf": [
				        {"type":"string","minLength":1,"description":"A single project name, workspace path or absolute path."},
				        {"type":"array","minItems":1,"maxItems":200,"items":{"type":"string"},
				         "description":"Project names, workspace paths such as /my.project/src/Example.java, or absolute filesystem paths. Mixed freely; each entry is classified on its own."}
				      ],
				      "description":"One name/path or a list of them; each entry is classified on its own."
				    },
				    "nearby": {"type":"integer","default":10,"minimum":0,"maximum":200,
				               "description":"Maximum number of project-name suggestions returned when an entry cannot be resolved."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		Object raw = arguments == null ? null : arguments.get("of"); //$NON-NLS-1$
		List<?> requested;
		if (raw instanceof String single) {
			requested = List.of(single);
		} else if (raw instanceof List<?> list && !list.isEmpty()) {
			requested = list;
		} else {
			return McpToolResult.error("The argument 'of' is required: one name/path string or a non-empty array of them."); //$NON-NLS-1$
		}
		int nearbyCount = arguments == null ? 10 : clampedNearby(arguments.get("nearby")); //$NON-NLS-1$
		JsonArray resolved = new JsonArray();
		for (Object entry : requested) {
			if (entry != null) {
				resolved.add(resolve(String.valueOf(entry).strip(), nearbyCount));
			}
		}
		return McpToolResult.of(new JsonObject().put("resolved", resolved) //$NON-NLS-1$
				.put("note", "pathInRepository is relative to repositoryRoot, so 'git -C <repositoryRoot> log --follow -- <pathInRepository>' runs as given.") //$NON-NLS-1$ //$NON-NLS-2$
				.toString());
	}

	private static int clampedNearby(Object value) {
		if (value instanceof Number number) {
			return Math.max(0, Math.min(200, number.intValue()));
		}
		return 10;
	}

	private static JsonObject resolve(String query, int nearbyCount) {
		JsonObject json = new JsonObject().put("query", query); //$NON-NLS-1$
		if (query.isEmpty()) {
			return json.put("resolved", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "Empty entry.") //$NON-NLS-1$
					.put("repositoryRoot", null) //$NON-NLS-1$
					.put("pathInRepository", null); //$NON-NLS-1$
		}
		IResource resource = asWorkspacePath(query);
		if (resource == null) {
			resource = asProjectName(query);
		}
		File onDisk = null;
		if (resource == null) {
			onDisk = new File(query);
			if (!onDisk.isAbsolute()) {
				return json.put("resolved", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No project, workspace path or absolute path named '%s'.".formatted(query)) //$NON-NLS-1$ //$NON-NLS-2$
						.put("nearby", nearbyProjects(query, nearbyCount)); //$NON-NLS-1$
			}
			resource = forLocation(onDisk);
		}
		if (resource != null) {
			IPath location = resource.getLocation();
			IProject project = resource.getProject();
			json.put("resolved", Boolean.TRUE) //$NON-NLS-1$
					.put("project", project == null ? null : project.getName()) //$NON-NLS-1$
					.put("workspacePath", resource.getFullPath().toString()) //$NON-NLS-1$
					.put("location", location == null ? null : location.toOSString()) //$NON-NLS-1$
					.put("exists", Boolean.valueOf(resource.exists())) //$NON-NLS-1$
					.put("open", project == null ? null : Boolean.valueOf(project.isOpen())); //$NON-NLS-1$
			onDisk = location == null ? onDisk : location.toFile();
		} else {
			json.put("resolved", Boolean.TRUE) //$NON-NLS-1$
					.put("project", null) //$NON-NLS-1$
					.put("workspacePath", null) //$NON-NLS-1$
					.put("location", onDisk.getAbsolutePath()) //$NON-NLS-1$
					.put("exists", Boolean.valueOf(onDisk.exists())) //$NON-NLS-1$
					.put("note", "Outside the workspace, so only the repository mapping is available."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return withRepository(json, onDisk);
	}

	/**
	 * A workspace path /project/... when its first segment names a project.
	 * The leading slash is optional: "kz036/Core/Src/main.c" resolves the same
	 * way as "/kz036/Core/Src/main.c", because the first segment decides.
	 */
	private static IResource asWorkspacePath(String query) {
		if (query.startsWith("/")) { //$NON-NLS-1$
			query = query.substring(1);
		}
		IPath path = new Path(query);
		if (path.segmentCount() == 0) {
			return null;
		}
		// An absolute filesystem path (Windows drive letter) is for forLocation.
		if (path.isAbsolute() || path.getDevice() != null) {
			return null;
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(path.segment(0));
		if (!project.exists()) {
			return null;
		}
		IPath fullPath = new Path("/").append(path); //$NON-NLS-1$
		return path.segmentCount() == 1 ? project : ResourcesPlugin.getWorkspace().getRoot().findMember(fullPath);
	}

	private static IResource asProjectName(String query) {
		// getProject asserts rather than returning null, and an absolute path reaches
		// here as a perfectly ordinary string
		if (!ResourcesPlugin.getWorkspace().validateName(query, IResource.PROJECT).isOK()) {
			return null;
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(query);
		return project.exists() ? project : null;
	}

	/**
	 * The workspace resource for a path on disk, taking the longest matching project
	 * location so a nested project wins over the one containing it.
	 */
	private static IResource forLocation(File file) {
		IPath path = new Path(file.getAbsolutePath());
		IResource best = null;
		int longest = -1;
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			IPath location = project.getLocation();
			if (location == null || !location.isPrefixOf(path) || location.segmentCount() <= longest) {
				continue;
			}
			longest = location.segmentCount();
			IPath inside = path.removeFirstSegments(location.segmentCount());
			best = inside.isEmpty() ? project : project.findMember(inside);
			if (best == null) {
				// on disk but not in the resource tree yet: a handle, so the location stays the requested one
				best = project.getFile(inside);
			}
		}
		return best;
	}

	/**
	 * Project names close to the query: first plain substrings, then subsequence
	 * matches as a fuzzier fallback (e.g. "kz36" for "kz036"), up to the
	 * requested number of suggestions.
	 */
	private static JsonArray nearbyProjects(String query, int limit) {
		JsonArray nearby = new JsonArray();
		if (limit <= 0) {
			return nearby;
		}
		String needle = query.toLowerCase(Locale.ROOT);
		String[] all = java.util.Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
				.map(IProject::getName).toArray(String[]::new);
		java.util.Set<String> picked = new java.util.HashSet<>();
		for (String name : all) {
			if (nearby.size() >= limit) {
				break;
			}
			if (name.toLowerCase(Locale.ROOT).contains(needle)) {
				nearby.add(name);
				picked.add(name);
			}
		}
		if (nearby.size() < limit) {
			for (String name : all) {
				if (nearby.size() >= limit) {
					break;
				}
				if (!picked.contains(name) && isSubsequence(needle, name.toLowerCase(Locale.ROOT))) {
					nearby.add(name);
				}
			}
		}
		return nearby;
	}

	private static boolean isSubsequence(String needle, String haystack) {
		int at = 0;
		for (int i = 0; i < haystack.length() && at < needle.length(); i++) {
			if (haystack.charAt(i) == needle.charAt(at)) {
				at++;
			}
		}
		return at == needle.length();
	}

	/**
	 * Adds the repository the location sits in, found by walking up for {@code .git}.
	 * A linked worktree has a {@code .git} file rather than a directory, which is why
	 * existence and not directoryness is the test.
	 */
	private static JsonObject withRepository(JsonObject json, File location) {
		if (location == null) {
			return json;
		}
		for (File candidate = location; candidate != null; candidate = candidate.getParentFile()) {
			if (new File(candidate, ".git").exists()) { //$NON-NLS-1$
				IPath root = new Path(candidate.getAbsolutePath());
				IPath full = new Path(location.getAbsolutePath());
				return json.put("repositoryRoot", candidate.getAbsolutePath()) //$NON-NLS-1$
						// without the device a Windows path came back as C:Example.java
						.put("pathInRepository", //$NON-NLS-1$
								full.removeFirstSegments(root.segmentCount()).setDevice(null).toString());
			}
		}
		return json.put("repositoryRoot", null) //$NON-NLS-1$
				.put("pathInRepository", null); //$NON-NLS-1$
	}
}
