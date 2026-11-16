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
                + "definition of a symbol when you only know its name. Optionally scoped to a single project.";
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
                    "contextLines":  {"type":"integer","default":15,"minimum":1,"maximum":200,"description":"Number of source lines to include above and below the definition."}
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
                for (IIndexBinding b : bindings) {
                    IIndexName[] defs = index.findDefinitions(b);
                    if (defs.length == 0) {
                        continue;
                    }
                    JsonObject desc = CdtIndexHelper.describeWithSnippet(defs[0], b, contextLines);
                    desc.put("matchCount", bindings.length);
                    return McpToolResult.of(desc.toString());
                }
            } finally {
                index.releaseReadLock();
            }
        } catch (CoreException | InterruptedException e) {
            return McpToolResult.error("Index query failed: " + e.getMessage());
        }
        return McpToolResult.error("No definition found for symbol '" + name + "'.");
    }
}
