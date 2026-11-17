package com.vogella.eclipse.mcp.cdt.internal;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.cdt.core.index.IIndex;
import org.eclipse.cdt.core.index.IIndexBinding;
import org.eclipse.cdt.core.index.IIndexManager;
import org.eclipse.cdt.core.index.IIndexName;
import org.eclipse.cdt.core.index.IndexFilter;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Finds all references (call sites / uses) of a symbol in the CDT C/C++ index.
 */
public final class FindReferencesTool implements IMcpTool {

    @Override
    public String getName() {
        return "cdt_find_references";
    }

    @Override
    public String getDescription() {
        return "Finds all references (call sites and uses) of a C/C++ symbol in the CDT index and "
                + "returns their locations (file path and line number). Optionally scoped to a single project. "
                + "Each reference carries the source line it points at, so a client can tell a call from a "
                + "declaration without reopening the file; pass includeLineText false for a compact list.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name":            {"type":"string","description":"Substring of the symbol name whose references to find."},
                    "project":         {"type":"string","description":"Optional C/C++ project name to scope the search."},
                    "caseSensitive":   {"type":"boolean","default":true},
                    "includeLineText": {"type":"boolean","default":true,"description":"Attach the source line of each reference."},
                    "maxResults":      {"type":"integer","default":50,"minimum":1,"maximum":500}
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
        boolean includeLineText = args.getBoolean("includeLineText", true);
        int maxResults = args.getInt("maxResults", 50, 1, 500);

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

        JsonArray matches = new JsonArray();
        int total = 0;
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
                    for (IIndexName n : index.findReferences(b)) {
                        if (monitor.isCanceled()) {
                            return McpToolResult.error("The request was cancelled.");
                        }
                        if (total >= maxResults) {
                            break;
                        }
                        JsonObject reference = CdtIndexHelper.describe(n, b, false);
                        if (includeLineText) {
                            String path = CdtIndexHelper.locationPath(n);
                            int line = CdtIndexHelper.locationLine(n);
                            if (!path.isEmpty() && line > 0) {
                                String source = CdtIndexHelper.lineText(path, line);
                                if (source != null) {
                                    reference.put("lineText", source.stripTrailing());
                                }
                            }
                        }
                        matches.add(reference);
                        total++;
                    }
                    if (total >= maxResults) {
                        break;
                    }
                }
            } finally {
                index.releaseReadLock();
            }
        } catch (CoreException | InterruptedException e) {
            return McpToolResult.error("Index query failed: " + e.getMessage());
        }

        JsonObject result = new JsonObject()
                .put("query", name)
                .put("total", total)
                .put("references", matches);
        return McpToolResult.of(result.toString());
    }
}
