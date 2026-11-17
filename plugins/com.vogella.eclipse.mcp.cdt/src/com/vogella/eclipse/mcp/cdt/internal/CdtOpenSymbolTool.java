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
 * Resolves a C/C++ symbol to its definition and returns the definition location
 * together with a snippet of the surrounding source. This lets an MCP client
 * "open" a symbol by name without knowing which file it lives in.
 */
public final class CdtOpenSymbolTool implements IMcpTool {

    @Override
    public String getName() {
        return "cdt_open_symbol";
    }

    @Override
    public String getDescription() {
        return "Resolves a C/C++ symbol (function, variable, type, macro) to its definition and returns the "
                + "file path, line number and a snippet of the surrounding source code. Use this to read the "
                + "definition of a symbol when you only know its name. Optionally scoped to a single project. "
                + "When several bindings match, nth picks the 1-based one, or allDefinitions returns each "
                + "binding's definition up to maxDefinitions, which is how overloads are inspected. "
                + "contextLines is capped at 200 so a single open cannot flood the answer.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name":          {"type":"string","description":"Exact or substring symbol name to open."},
                    "project":       {"type":"string","description":"Optional C/C++ project name to scope the search."},
                    "caseSensitive": {"type":"boolean","default":true},
                    "nth":           {"type":"integer","default":1,"minimum":1,"description":"1-based index of the binding to open when several match. Ignored when allDefinitions is true."},
                    "allDefinitions":{"type":"boolean","default":false,"description":"Return every matching binding's definition instead of just one."},
                    "maxDefinitions":{"type":"integer","default":20,"minimum":1,"maximum":100,"description":"Cap on definitions returned when allDefinitions is true."},
                    "contextLines":  {"type":"integer","default":15,"minimum":1,"maximum":200,"description":"Number of source lines above and below each definition, capped at 200."}
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
        int nth = args.getInt("nth", 1, 1, Integer.MAX_VALUE);
        boolean allDefinitions = args.getBoolean("allDefinitions", false);
        int maxDefinitions = args.getInt("maxDefinitions", 20, 1, 100);
        int contextLines = args.getInt("contextLines", 15, 1, 200);

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

        try {
            IIndexManager mgr = CdtIndexHelper.indexManager();
            IIndex index = mgr.getIndex(projects.toArray(new ICProject[0]));
            if (index == null) {
                return McpToolResult.error("CDT index is not available for the selected project(s).");
            }
            index.acquireReadLock();
            try {
                IIndexBinding[] bindings = index.findBindings(pattern, caseSensitive, IndexFilter.ALL, monitor);
                JsonArray definitions = new JsonArray();
                int withDefinition = 0;
                for (IIndexBinding b : bindings) {
                    IIndexName[] defs = index.findDefinitions(b);
                    if (defs.length == 0) {
                        continue;
                    }
                    withDefinition++;
                    if (allDefinitions) {
                        if (definitions.size() >= maxDefinitions) {
                            break;
                        }
                        definitions.add(CdtIndexHelper.describeWithSnippet(defs[0], b, contextLines));
                        continue;
                    }
                    if (withDefinition == nth) {
                        JsonObject desc = CdtIndexHelper.describeWithSnippet(defs[0], b, contextLines);
                        desc.put("matchCount", bindings.length).put("nth", Integer.valueOf(withDefinition));
                        return McpToolResult.of(desc.toString());
                    }
                }
                if (allDefinitions) {
                    return McpToolResult.of(new JsonObject()
                            .put("query", name)
                            .put("matchCount", Integer.valueOf(bindings.length))
                            .put("returned", Integer.valueOf(definitions.size()))
                            .put("truncated", Boolean.valueOf(withDefinition > definitions.size()))
                            .put("definitions", definitions)
                            .toString());
                }
            } finally {
                index.releaseReadLock();
            }
        } catch (CoreException | InterruptedException e) {
            return McpToolResult.error("Index query failed: " + e.getMessage());
        }
        return McpToolResult.error("No definition found for symbol '" + name + "' at nth " + nth + ".");
    }
}
