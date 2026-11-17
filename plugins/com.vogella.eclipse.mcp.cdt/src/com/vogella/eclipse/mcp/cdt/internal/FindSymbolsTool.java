package com.vogella.eclipse.mcp.cdt.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.cdt.core.index.IIndex;
import org.eclipse.cdt.core.index.IIndexBinding;
import org.eclipse.cdt.core.index.IIndexManager;
import org.eclipse.cdt.core.index.IIndexName;
import org.eclipse.cdt.core.index.IndexFilter;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Searches the CDT C/C++ index (PDOM) for symbols whose name contains the given
 * text and returns their definition locations. Lets an MCP client navigate a C/C++
 * project without opening files.
 */
public final class FindSymbolsTool implements IMcpTool {

    @Override
    public String getName() {
        return "cdt_find_symbols";
    }

    @Override
    public String getDescription() {
        return "Searches the CDT C/C++ index for symbols whose name contains the given text and "
                + "returns their definition locations (file path and line number). Use this to navigate "
                + "a C/C++ project without opening files. Optionally scoped to a single project. The "
                + "total counts every filtered match, not just the page returned, so offset can page "
                + "through all of them; kind and path narrow by binding kind and definition path.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name":          {"type":"string","description":"Substring of the symbol name to find."},
                    "project":       {"type":"string","description":"Optional C/C++ project name to scope the search."},
                    "caseSensitive": {"type":"boolean","default":true},
                    "kind":          {"oneOf":[{"type":"string"},{"type":"array","items":{"type":"string"}}],"description":"Only these binding kinds: function, variable, type, macro, other. A string or an array."},
                    "path":          {"type":"string","description":"Glob over the definition path, e.g. */Replace/* to restrict to one source set."},
                    "offset":        {"type":"integer","default":0,"minimum":0,"description":"Skip this many matches, for paging through the full result."},
                    "maxResults":    {"type":"integer","default":50,"minimum":1,"maximum":500}
                  },
                  "additionalProperties": false
                }""";
    }

    @Override
    public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
        ToolArguments args = ToolArguments.of(arguments);
        String name = args.getString("name");
        if (name == null || name.isBlank()) {
            return McpToolResult.error("The argument 'name' is required.");
        }
        String project = args.getString("project");
        boolean caseSensitive = args.getBoolean("caseSensitive", true);
        int offset = args.getInt("offset", 0, 0, Integer.MAX_VALUE);
        int maxResults = args.getInt("maxResults", 50, 1, 500);
        List<String> kinds = stringList(arguments, "kind");
        Pattern pathGlob;
        try {
            pathGlob = Globs.compile(args.getString("path"));
        } catch (PatternSyntaxException e) {
            return McpToolResult.error("Could not read 'path' as a glob: " + e.getMessage());
        }

        List<ICProject> projects;
        try {
            projects = CdtIndexHelper.resolveProjects(project);
        } catch (CoreException e) {
            return McpToolResult.error("Failed to resolve projects: " + e.getMessage());
        }
        if (projects.isEmpty()) {
            return McpToolResult.error(project != null
                    ? "Project '" + project + "' is not an accessible C/C++ project."
                    : "No accessible C/C++ projects found in the workspace.");
        }

        Pattern pattern = Pattern.compile(".*" + Pattern.quote(name) + ".*",
                caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);

        List<JsonObject> all = new ArrayList<>();
        try {
            IIndexManager mgr = CdtIndexHelper.indexManager();
            IIndex index = mgr.getIndex(projects.toArray(new ICProject[0]));
            if (index == null) {
                return McpToolResult.error("CDT index is not available for the selected project(s).");
            }
            index.acquireReadLock();
            try {
                IIndexBinding[] bindings = index.findBindings(pattern, caseSensitive, IndexFilter.ALL, monitor);
                for (IIndexBinding b : bindings) {
                    if (monitor.isCanceled()) {
                        return McpToolResult.error("The request was cancelled.");
                    }
                    if (!kinds.isEmpty() && !kinds.contains(CdtIndexHelper.kindOf(b))) {
                        continue;
                    }
                    for (IIndexName n : index.findDefinitions(b)) {
                        String location = CdtIndexHelper.locationPath(n);
                        if (pathGlob != null && !pathGlob.matcher(location).matches()) {
                            continue;
                        }
                        all.add(CdtIndexHelper.describe(n, b, true));
                    }
                }
            } finally {
                index.releaseReadLock();
            }
        } catch (CoreException | InterruptedException e) {
            return McpToolResult.error("Index query failed: " + e.getMessage());
        }

        JsonArray matches = new JsonArray();
        int from = Math.min(offset, all.size());
        int to = Math.min(from + maxResults, all.size());
        for (JsonObject match : all.subList(from, to)) {
            matches.add(match);
        }
        JsonObject result = new JsonObject()
                .put("query", name)
                .put("total", all.size())
                .put("offset", offset)
                .put("returned", matches.size())
                .put("truncated", Boolean.valueOf(to < all.size()))
                .put("matches", matches);
        if (!kinds.isEmpty()) {
            JsonArray kindArray = new JsonArray();
            kinds.forEach(kindArray::add);
            result.put("kind", kindArray);
        }
        return McpToolResult.of(result.toString());
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
}
