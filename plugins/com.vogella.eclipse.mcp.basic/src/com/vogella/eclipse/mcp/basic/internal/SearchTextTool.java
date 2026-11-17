package com.vogella.eclipse.mcp.basic.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Searches the text of workspace files.
 */
public final class SearchTextTool implements IMcpTool {

	private static final int MAX_LINE_LENGTH = 500;

	@Override
	public String getName() {
		return "eclipse_search_text"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Searches the text of workspace files, including the ones the Java model cannot see: plugin.xml, .exsd schemas, .project, manifests, properties. Read-only. For Java elements eclipse_find_references answers better, because it resolves overloads and inheritance and this does not; this is for everything that is not Java, and for a client that has no filesystem of its own because the IDE is on another machine or hidden. It searches through Eclipse's own text search, so resources Eclipse marks derived are excluded by default rather than returned as duplicate noise, and fileNamePattern narrows to the files that matter. Maven and Gradle output under target and build is NOT marked derived and is therefore not covered by that; use excludePathPattern, for example */target/*, to drop it. excludeGenerated additionally applies the 'excluding' paths declared in each project's .cproject sourceEntries, which is how a CDT project itself says which sources it does not build, so generated and substituted sources stop dominating the hits. grouped returns the same hits grouped by project and file and contextLines adds the neighbouring lines with their numbers; the flat matches list stays. compareSourceSets takes exactly two CDT source paths, for example Replace and Core, and pairs hits by the path relative to the source set, marking each pair same, different or only-in-one-side, which is how a handwritten replacement and its generated counterpart are compared."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["pattern"],
				  "properties": {
				    "pattern":         {"type":"string","description":"Text to find, or a regular expression when isRegex is true."},
				    "isRegex":         {"type":"boolean","default":false,"description":"Read pattern as a Java regular expression instead of as literal text."},
				    "isCaseSensitive": {"type":"boolean","default":false},
				    "projects":        {"type":"array","items":{"type":"string"},"description":"Restrict to these projects. Omit for the whole workspace."},
				    "path":            {"type":"string","description":"Restrict to this workspace folder or file, e.g. /app/src."},
				    "fileNamePattern": {"type":"string","description":"Glob over file names, e.g. *.exsd or plugin.xml. Omit for every file."},
				    "excludePathPattern": {"type":"string","description":"Glob over the workspace path, matches are skipped, e.g. */target/* to drop Maven build output."},
				    "excludeGenerated": {"type":"boolean","default":false,"description":"Apply the 'excluding' paths from each project's .cproject sourceEntries, so sources CDT itself does not build are skipped."},
				    "grouped":         {"type":"boolean","default":false,"description":"Also return hits grouped by project and file. The flat matches list stays."},
				    "contextLines":    {"type":"integer","default":0,"minimum":0,"maximum":5,"description":"Include this many numbered lines before and after each match."},
				    "compareSourceSets": {"type":"array","items":{"type":"string"},"minItems":2,"maxItems":2,"description":"Exactly two CDT source paths, e.g. ['Replace','Core']. Adds a comparison pairing files by the path relative to each source set, marked same/different/only-in-first/only-in-second."},
				    "refresh":         {"type":"boolean","default":true,"description":"Read changes made outside the IDE into the searched scope first."},
				    "includeDerived":  {"type":"boolean","default":false,"description":"Include derived resources, which is build output."},
				    "maxResults":      {"type":"integer","default":200,"minimum":1,"maximum":5000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String text = args.getString("pattern"); //$NON-NLS-1$
		if (text == null) {
			return McpToolResult.error("The argument 'pattern' is required."); //$NON-NLS-1$
		}
		boolean isRegex = args.getBoolean("isRegex", false); //$NON-NLS-1$
		boolean caseSensitive = args.getBoolean("isCaseSensitive", false); //$NON-NLS-1$
		boolean includeDerived = args.getBoolean("includeDerived", false); //$NON-NLS-1$
		boolean excludeGenerated = args.getBoolean("excludeGenerated", false); //$NON-NLS-1$
		boolean grouped = args.getBoolean("grouped", false); //$NON-NLS-1$
		int contextLines = args.getInt("contextLines", 0, 0, 5); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 5000); //$NON-NLS-1$
		List<String> compareSourceSets = stringList(arguments, "compareSourceSets"); //$NON-NLS-1$
		if (!compareSourceSets.isEmpty() && compareSourceSets.size() != 2) {
			return McpToolResult.error("'compareSourceSets' must name exactly two source paths, e.g. Replace and Core."); //$NON-NLS-1$
		}

		Pattern pattern;
		try {
			pattern = TextSearchEngine.createPattern(text, caseSensitive, isRegex);
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'pattern' as a regular expression: " + e.getMessage()); //$NON-NLS-1$
		}
		Pattern excluded;
		try {
			excluded = Globs.compile(args.getString("excludePathPattern")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'excludePathPattern' as a glob: " + e.getMessage()); //$NON-NLS-1$
		}
		Pattern fileNames;
		try {
			fileNames = Globs.compile(args.getString("fileNamePattern")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'fileNamePattern' as a glob: " + e.getMessage()); //$NON-NLS-1$
		}

		List<IResource> roots = new ArrayList<>();
		String path = args.getString("path"); //$NON-NLS-1$
		if (path != null) {
			IResource resource = ResourcesPlugin.getWorkspace().getRoot()
					.findMember(IPath.fromPortableString(path));
			if (resource == null) {
				return McpToolResult.error("No workspace resource at '%s'.".formatted(path)); //$NON-NLS-1$
			}
			roots.add(resource);
		}
		for (String name : ProjectSelection.names(arguments)) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
			if (!project.isAccessible()) {
				return McpToolResult.error("No open project named '%s' in this workspace.".formatted(name)); //$NON-NLS-1$
			}
			roots.add(project);
		}
		if (roots.isEmpty()) {
			roots.add(ResourcesPlugin.getWorkspace().getRoot());
		}

		if (args.getBoolean("refresh", true)) { //$NON-NLS-1$
			for (IResource root : roots) {
				try {
					WorkspaceSync.refresh(root, monitor);
				} catch (org.eclipse.core.runtime.CoreException e) {
					// an unrefreshable scope is still searchable, and stale beats nothing
				}
			}
		}
		Map<String, Set<String>> exclusions = new HashMap<>();
		if (excludeGenerated) {
			for (IResource root : roots) {
				IProject project = root.getProject();
				if (project != null) {
					exclusions.put(project.getName(), cProjectExclusions(project));
				}
			}
		}
		Collector collector = new Collector(maxResults, excluded, exclusions, contextLines, compareSourceSets);
		TextSearchScope scope = TextSearchScope.newSearchScope(roots.toArray(IResource[]::new),
				fileNames == null ? Pattern.compile(".*") : fileNames, includeDerived); //$NON-NLS-1$
		TextSearchEngine.createDefault().search(scope, collector, pattern, monitor);

		JsonObject result = new JsonObject().put("pattern", text) //$NON-NLS-1$
				.put("isRegex", Boolean.valueOf(isRegex)) //$NON-NLS-1$
				.put("isCaseSensitive", Boolean.valueOf(caseSensitive)) //$NON-NLS-1$
				.put("includeDerived", Boolean.valueOf(includeDerived)) //$NON-NLS-1$
				.put("excludeGenerated", Boolean.valueOf(excludeGenerated)) //$NON-NLS-1$
				.put("contextLines", Integer.valueOf(contextLines)) //$NON-NLS-1$
				.put("total", Integer.valueOf(collector.distinct.size())) //$NON-NLS-1$
				.put("files", Integer.valueOf(collector.locations.size())) //$NON-NLS-1$
				.put("duplicatePathsCollapsed", Integer.valueOf(collector.collapsed)) //$NON-NLS-1$
				.put("excludedByPath", Integer.valueOf(collector.excludedMatches)) //$NON-NLS-1$
				.put("excludedByCProject", Integer.valueOf(collector.excludedByCProject)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(collector.distinct.size() > maxResults)) //$NON-NLS-1$
				.put("matches", collector.matches(maxResults)); //$NON-NLS-1$
		if (grouped) {
			result.put("groups", collector.groups(maxResults)); //$NON-NLS-1$
		}
		if (!compareSourceSets.isEmpty()) {
			JsonArray sets = new JsonArray();
			compareSourceSets.forEach(sets::add);
			result.put("compareSourceSets", sets) //$NON-NLS-1$
					.put("comparisons", collector.comparisons(maxResults)); //$NON-NLS-1$
		}
		if (collector.collapsed > 0) {
			result.put("note", //$NON-NLS-1$
					"%d further workspace path(s) reach the same file on disk and were collapsed. Nested projects make one file reachable through several paths, and counting those separately inflates a result in a way a client cannot detect without a filesystem. alsoVisibleAs lists the other paths." //$NON-NLS-1$
							.formatted(Integer.valueOf(collector.collapsed)));
		}
		return McpToolResult.of(result.toString());
	}

	/** The paths a CDT project declares it does not build, relative to the project root. */
	private static Set<String> cProjectExclusions(IProject project) {
		Set<String> result = new LinkedHashSet<>();
		IPath location = project.getLocation();
		if (location == null) {
			return result;
		}
		Path file = location.toFile().toPath().resolve(".cproject"); //$NON-NLS-1$
		if (!Files.isReadable(file)) {
			return result;
		}
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
			NodeList entries = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("entry"); //$NON-NLS-1$
			for (int i = 0; i < entries.getLength(); i++) {
				Element entry = (Element) entries.item(i);
				String excluding = entry.getAttribute("excluding"); //$NON-NLS-1$
				if (excluding == null || excluding.isBlank()) {
					continue;
				}
				String source = entry.getAttribute("name").strip(); //$NON-NLS-1$
				for (String item : excluding.split("\\|")) { //$NON-NLS-1$
					String relative = item.strip();
					if (!relative.isEmpty()) {
						result.add(source.isEmpty() ? relative : source + "/" + relative); //$NON-NLS-1$
					}
				}
			}
		} catch (Exception e) {
			// a project with no readable .cproject simply contributes no exclusions
		}
		return result;
	}

	private static List<String> stringList(Map<String, Object> arguments, String name) {
		Object raw = arguments == null ? null : arguments.get(name);
		if (raw instanceof List<?> list) {
			return list.stream().map(String::valueOf).map(String::strip).filter(value -> !value.isEmpty()).toList();
		}
		return List.of();
	}

	/** Source set and path under it, for /project/sourceSet/rest/of/path. */
	private static String[] splitSourceSet(String path) {
		String[] parts = path.split("/", 4); //$NON-NLS-1$
		if (parts.length < 3 || parts[2].isEmpty()) {
			return new String[] { null, path };
		}
		return new String[] { parts[2], parts.length > 3 ? parts[3] : "" }; //$NON-NLS-1$
	}

	private record MatchInfo(String project, String path, String sourceSet, String relativePath, int line,
			String text) {
	}

	/** Reports one line per match, with optional context and grouping. */
	private static final class Collector extends TextSearchRequestor {

		private final Map<String, JsonObject> distinct = new LinkedHashMap<>();

		private final Map<String, MatchInfo> infos = new LinkedHashMap<>();

		private final Map<String, JsonArray> alsoVisibleAs = new HashMap<>();

		private final Set<String> locations = new LinkedHashSet<>();

		private final int maxResults;

		private final Pattern excluded;

		private final Map<String, Set<String>> exclusions;

		private final int contextLines;

		private final List<String> compareSourceSets;

		private int collapsed;

		private int excludedMatches;

		private int excludedByCProject;

		private IFile currentFile;

		private int[] lineStarts;

		Collector(int maxResults, Pattern excluded, Map<String, Set<String>> exclusions, int contextLines,
				List<String> compareSourceSets) {
			this.maxResults = maxResults;
			this.excluded = excluded;
			this.exclusions = exclusions;
			this.contextLines = contextLines;
			this.compareSourceSets = compareSourceSets;
		}

		@Override
		public boolean acceptFile(IFile file) {
			currentFile = null;
			lineStarts = null;
			return true;
		}

		@Override
		public boolean reportBinaryFile(IFile file) {
			return false;
		}

		@Override
		public boolean canRunInParallel() {
			return false;
		}

		@Override
		public boolean acceptPatternMatch(TextSearchMatchAccess match) {
			IFile file = match.getFile();
			String path = file.getFullPath().toString();
			if (excluded != null && excluded.matcher(path).matches()) {
				excludedMatches++;
				return true;
			}
			String projectName = file.getProject().getName();
			Set<String> projectExclusions = exclusions.get(projectName);
			if (projectExclusions != null && !projectExclusions.isEmpty()) {
				String prefix = "/" + projectName + "/"; //$NON-NLS-1$
				for (String skipped : projectExclusions) {
					if (path.equals(prefix + skipped) || path.startsWith(prefix + skipped + "/")) { //$NON-NLS-1$
						excludedByCProject++;
						return true;
					}
				}
			}
			int offset = match.getMatchOffset();
			String location = file.getLocationURI() == null ? path : file.getLocationURI().toString();
			locations.add(location);
			String key = location + "@" + offset; //$NON-NLS-1$
			if (distinct.containsKey(key)) {
				collapsed++;
				if (distinct.get(key) != null) {
					alsoVisibleAs.computeIfAbsent(key, ignored -> new JsonArray()).add(path);
				}
				return true;
			}
			if (distinct.size() >= maxResults) {
				distinct.put(key, null);
				return true;
			}
			int start = offset;
			while (start > 0 && match.getFileContentChar(start - 1) != '\n') { //$NON-NLS-1$
				start--;
			}
			int length = match.getFileContentLength();
			int end = offset + match.getMatchLength();
			while (end < length && match.getFileContentChar(end) != '\n') { //$NON-NLS-1$
				end++;
			}
			String line = match.getFileContent(start, Math.min(end, length) - start).stripTrailing();
			int lineNumber = lineOf(match, offset);
			String[] source = splitSourceSet(path);
			JsonObject entry = new JsonObject().put("file", path) //$NON-NLS-1$
					.put("line", Integer.valueOf(lineNumber)) //$NON-NLS-1$
					.put("offset", Integer.valueOf(offset)) //$NON-NLS-1$
					.put("text", line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) : line); //$NON-NLS-1$
			if (!compareSourceSets.isEmpty()) {
				entry.put("sourceSet", source[0]); //$NON-NLS-1$
			}
			if (contextLines > 0) {
				entry.put("context", contextOf(match, lineNumber)); //$NON-NLS-1$
			}
			distinct.put(key, entry);
			infos.put(key, new MatchInfo(projectName, path, source[0], source[1], lineNumber, line));
			return true;
		}

		JsonArray matches(int limit) {
			JsonArray array = new JsonArray();
			for (Map.Entry<String, JsonObject> entry : distinct.entrySet()) {
				if (array.size() >= limit) {
					break;
				}
				JsonArray others = alsoVisibleAs.get(entry.getKey());
				if (others != null) {
					entry.getValue().put("alsoVisibleAs", others); //$NON-NLS-1$
				}
				array.add(entry.getValue());
			}
			return array;
		}

		JsonArray groups(int limit) {
			Map<String, Map<String, JsonArray>> byProject = new LinkedHashMap<>();
			int count = 0;
			for (Map.Entry<String, JsonObject> entry : distinct.entrySet()) {
				if (entry.getValue() == null || count++ >= limit) {
					continue;
				}
				MatchInfo info = infos.get(entry.getKey());
				byProject.computeIfAbsent(info.project(), ignored -> new LinkedHashMap<>())
						.computeIfAbsent(info.path(), ignored -> new JsonArray()).add(entry.getValue());
			}
			JsonArray result = new JsonArray();
			for (Map.Entry<String, Map<String, JsonArray>> project : byProject.entrySet()) {
				JsonArray files = new JsonArray();
				for (Map.Entry<String, JsonArray> file : project.getValue().entrySet()) {
					files.add(new JsonObject().put("file", file.getKey()).put("matches", file.getValue())); //$NON-NLS-1$ //$NON-NLS-2$
				}
				result.add(new JsonObject().put("project", project.getKey()).put("files", files)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return result;
		}

		JsonArray comparisons(int limit) {
			Map<String, Map<String, List<MatchInfo>>> paired = new LinkedHashMap<>();
			for (MatchInfo info : infos.values()) {
				if (info.sourceSet() == null || !compareSourceSets.contains(info.sourceSet())) {
					continue;
				}
				paired.computeIfAbsent(info.project() + "|" + info.relativePath(), ignored -> new LinkedHashMap<>()) //$NON-NLS-1$
						.computeIfAbsent(info.sourceSet(), ignored -> new ArrayList<>()).add(info);
			}
			JsonArray result = new JsonArray();
			for (Map.Entry<String, Map<String, List<MatchInfo>>> pair : paired.entrySet()) {
				if (result.size() >= limit) {
					break;
				}
				List<MatchInfo> first = pair.getValue().get(compareSourceSets.get(0));
				List<MatchInfo> second = pair.getValue().get(compareSourceSets.get(1));
				String status = first == null ? "only-in-second" //$NON-NLS-1$
						: second == null ? "only-in-first" : same(first, second) ? "same" : "different"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				String[] where = pair.getKey().split("\\|", 2); //$NON-NLS-1$
				result.add(new JsonObject().put("project", where[0]) //$NON-NLS-1$
						.put("relativePath", where[1]) //$NON-NLS-1$
						.put("first", side(first)) //$NON-NLS-1$
						.put("second", side(second)) //$NON-NLS-1$
						.put("status", status)); //$NON-NLS-1$
			}
			return result;
		}

		private static boolean same(List<MatchInfo> first, List<MatchInfo> second) {
			if (first.size() != second.size()) {
				return false;
			}
			for (int i = 0; i < first.size(); i++) {
				if (first.get(i).line() != second.get(i).line()
						|| !first.get(i).text().equals(second.get(i).text())) {
					return false;
				}
			}
			return true;
		}

		private static JsonObject side(List<MatchInfo> matches) {
			if (matches == null) {
				return null;
			}
			JsonArray lines = new JsonArray();
			for (MatchInfo match : matches) {
				lines.add(new JsonObject().put("line", Integer.valueOf(match.line())).put("text", match.text())); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return new JsonObject().put("file", matches.get(0).path()).put("matches", lines); //$NON-NLS-1$ //$NON-NLS-2$
		}

		/** Numbered neighbouring lines from the same cached line starts used for the match. */
		private JsonArray contextOf(TextSearchMatchAccess match, int line) {
			JsonArray context = new JsonArray();
			int from = Math.max(1, line - contextLines);
			int to = Math.min(lineStarts.length, line + contextLines);
			for (int number = from; number <= to; number++) {
				int begin = lineStarts[number - 1];
				int end = number < lineStarts.length ? lineStarts[number] - 1 : match.getFileContentLength();
				if (end > begin && match.getFileContentChar(end - 1) == '\r') { //$NON-NLS-1$
					end--;
				}
				context.add(new JsonObject().put("line", Integer.valueOf(number)) //$NON-NLS-1$
						.put("text", match.getFileContent(begin, end - begin))); //$NON-NLS-1$
			}
			return context;
		}

		/** Line starts are computed once per file, since matches arrive grouped by file. */
		private int lineOf(TextSearchMatchAccess match, int offset) {
			if (currentFile != match.getFile() || lineStarts == null) {
				currentFile = match.getFile();
				List<Integer> starts = new ArrayList<>();
				starts.add(Integer.valueOf(0));
				int length = match.getFileContentLength();
				for (int i = 0; i < length; i++) {
					if (match.getFileContentChar(i) == '\n') { //$NON-NLS-1$
						starts.add(Integer.valueOf(i + 1));
					}
				}
				lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
			}
			int index = Arrays.binarySearch(lineStarts, offset);
			return index >= 0 ? index + 1 : -index - 1;
		}
	}
}
