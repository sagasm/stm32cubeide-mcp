package com.vogella.eclipse.mcp.cdt.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.cdt.core.dom.ast.IFunction;
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
 * Builds a caller and callee tree around a function from the CDT index.
 */
public final class CdtCallHierarchyTool implements IMcpTool {

    private static final Set<String> DIRECTIONS = Set.of("callers", "callees", "both");

    @Override
    public String getName() {
        return "cdt_call_hierarchy";
    }

    @Override
    public String getDescription() {
        return "Returns the callers and/or callees of a C/C++ function from the CDT index, recursively up "
                + "to maxDepth. Each node carries the file path and line of the definition or call site. "
                + "Callers come from the function enclosing each reference; callees come from the names "
                + "enclosed by the function definition, so a call through a macro or an indirect call may "
                + "not appear. Optionally scoped to one project.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name":          {"type":"string","description":"Substring of the function name to start from."},
                    "project":       {"type":"string","description":"Optional C/C++ project name to scope the search."},
                    "direction":     {"type":"string","enum":["callers","callees","both"],"default":"both","description":"Which side of the hierarchy to walk."},
                    "maxDepth":      {"type":"integer","default":2,"minimum":1,"maximum":8,"description":"How many levels to expand from each starting function."},
                    "maxResults":    {"type":"integer","default":50,"minimum":1,"maximum":500,"description":"Total number of nodes across all roots; expansion stops there."},
                    "caseSensitive": {"type":"boolean","default":true}
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
        String direction = args.getString("direction", "both");
        if (!DIRECTIONS.contains(direction)) {
            return McpToolResult.error("Unknown direction '%s', expected callers, callees or both.".formatted(direction));
        }
        int maxDepth = args.getInt("maxDepth", 2, 1, 8);
        int maxResults = args.getInt("maxResults", 50, 1, 500);
        boolean caseSensitive = args.getBoolean("caseSensitive", true);

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
        JsonArray roots = new JsonArray();
        int[] budget = { maxResults };
        Set<String> visited = new HashSet<>();
        try {
            IIndexManager mgr = CdtIndexHelper.indexManager();
            IIndex index = mgr.getIndex(projects.toArray(new ICProject[0]));
            if (index == null) {
                return McpToolResult.error("CDT index is not available for the selected project(s).");
            }
            index.acquireReadLock();
            try {
                IIndexBinding[] bindings = index.findBindings(pattern, caseSensitive, IndexFilter.ALL, monitor);
                for (IIndexBinding binding : bindings) {
                    if (!(binding instanceof IFunction) || budget[0] <= 0) {
                        continue;
                    }
                    IIndexName definition = firstName(index, binding);
                    if (definition == null || !visited.add("root:" + qualified(binding))) {
                        continue;
                    }
                    JsonObject node = CdtIndexHelper.describe(definition, binding, true);
                    budget[0]--;
                    if (!"callees".equals(direction)) {
                        JsonArray callers = new JsonArray();
                        expandCallers(index, binding, 1, maxDepth, callers, visited, budget);
                        node.put("callers", callers);
                    }
                    if (!"callers".equals(direction)) {
                        JsonArray callees = new JsonArray();
                        expandCallees(index, binding, 1, maxDepth, callees, visited, budget);
                        node.put("callees", callees);
                    }
                    roots.add(node);
                }
            } finally {
                index.releaseReadLock();
            }
        } catch (CoreException | InterruptedException e) {
            return McpToolResult.error("Index query failed: " + e.getMessage());
        }

        JsonObject result = new JsonObject()
                .put("query", name)
                .put("direction", direction)
                .put("maxDepth", Integer.valueOf(maxDepth))
                .put("total", Integer.valueOf(maxResults - budget[0]))
                .put("truncated", Boolean.valueOf(budget[0] <= 0))
                .put("roots", roots);
        return McpToolResult.of(result.toString());
    }

    private static void expandCallers(IIndex index, IIndexBinding binding, int depth, int maxDepth, JsonArray into,
            Set<String> visited, int[] budget) throws CoreException {
        if (depth > maxDepth || budget[0] <= 0) {
            return;
        }
        for (IIndexName reference : index.findReferences(binding)) {
            if (budget[0] <= 0) {
                return;
            }
            IIndexName enclosing = reference.getEnclosingDefinition();
            if (enclosing == null) {
                continue;
            }
            IIndexBinding caller = index.findBinding(enclosing);
            if (!(caller instanceof IFunction) || !visited.add("caller:" + qualified(caller))) {
                continue;
            }
            JsonObject node = CdtIndexHelper.describe(enclosing, caller, true);
            budget[0]--;
            JsonArray nested = new JsonArray();
            expandCallers(index, caller, depth + 1, maxDepth, nested, visited, budget);
            node.put("callers", nested);
            into.add(node);
        }
    }

    private static void expandCallees(IIndex index, IIndexBinding binding, int depth, int maxDepth, JsonArray into,
            Set<String> visited, int[] budget) throws CoreException {
        if (depth > maxDepth || budget[0] <= 0) {
            return;
        }
        for (IIndexName definition : index.findDefinitions(binding)) {
            for (IIndexName enclosed : definition.getEnclosedNames()) {
                if (budget[0] <= 0) {
                    return;
                }
                if (!enclosed.isReference()) {
                    continue;
                }
                IIndexBinding callee = index.findBinding(enclosed);
                if (!(callee instanceof IFunction) || !visited.add("callee:" + qualified(callee))) {
                    continue;
                }
                JsonObject node = CdtIndexHelper.describe(enclosed, callee, false);
                budget[0]--;
                JsonArray nested = new JsonArray();
                expandCallees(index, callee, depth + 1, maxDepth, nested, visited, budget);
                node.put("callees", nested);
                into.add(node);
            }
        }
    }

    private static IIndexName firstName(IIndex index, IIndexBinding binding) throws CoreException {
        IIndexName[] definitions = index.findDefinitions(binding);
        if (definitions.length > 0) {
            return definitions[0];
        }
        IIndexName[] references = index.findReferences(binding);
        return references.length > 0 ? references[0] : null;
    }

    private static String qualified(IIndexBinding binding) {
        String[] parts = binding.getQualifiedName();
        return parts != null && parts.length > 0 ? String.join("::", parts) : binding.getName();
    }
}
