package com.vogella.eclipse.mcp.cdt.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.cdt.core.dom.ast.ICompositeType;
import org.eclipse.cdt.core.dom.ast.IField;
import org.eclipse.cdt.core.dom.ast.IType;
import org.eclipse.cdt.core.dom.ast.ITypedef;
import org.eclipse.cdt.core.index.IIndex;
import org.eclipse.cdt.core.index.IIndexBinding;
import org.eclipse.cdt.core.index.IIndexFile;
import org.eclipse.cdt.core.index.IIndexFileLocation;
import org.eclipse.cdt.core.index.IIndexManager;
import org.eclipse.cdt.core.index.IIndexName;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the symbols a single indexed C/C++ file declares.
 */
public final class CdtOutlineTool implements IMcpTool {

    private static final Set<String> KINDS = Set.of("function", "variable", "type", "macro", "other");

    /** One outline entry plus the coordinate needed to detect macro-expansion collisions. */
    private record Slot(com.vogella.eclipse.mcp.core.json.JsonObject node, String path, int offset, int line) {
    }

    @Override
    public String getName() {
        return "cdt_outline";
    }

    @Override
    public String getDescription() {
        return "Lists the functions, variables, types and macros a C/C++ file declares, using the CDT "
                + "index. The path may be workspace relative (kz036/Core/Src/main.c), start with '/', "
                + "or be a filesystem path; an exact match wins over a suffix match. The index only "
                + "holds declarations the scanner saw, so a file that was never indexed reports an error "
                + "rather than an empty outline. maxDepth above 1 also lists the fields of composite "
                + "types, resolving a typedef to its underlying struct.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "required": ["path"],
                  "properties": {
                    "path":       {"type":"string","description":"File to outline, workspace or filesystem path."},
                    "project":    {"type":"string","description":"Optional C/C++ project name to scope the index."},
                    "maxDepth":   {"type":"integer","default":1,"minimum":1,"maximum":4,"description":"1 lists top-level declarations; above 1 also lists composite type fields."},
                    "kindFilter": {"oneOf":[{"type":"string"},{"type":"array","items":{"type":"string"}}],"description":"Only these kinds: function, variable, type, macro, other. Omit for all."},
                    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000}
                  },
                  "additionalProperties": false
                }""";
    }

    @Override
    public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
        ToolArguments args = ToolArguments.of(arguments);
        String path = args.getString("path");
        if (path == null || path.isBlank()) {
            return McpToolResult.error("The argument 'path' is required.");
        }
        String project = args.getString("project");
        int maxDepth = args.getInt("maxDepth", 1, 1, 4);
        int maxResults = args.getInt("maxResults", 200, 1, 2000);
        List<String> kinds = kinds(arguments, args);
        for (String kind : kinds) {
            if (!KINDS.contains(kind)) {
                return McpToolResult.error("Unknown kind filter '%s', expected one of %s.".formatted(kind, KINDS));
            }
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

        List<Slot> slots = new ArrayList<>();
        String matched = null;
        int total = 0;
        try {
            IIndexManager mgr = CdtIndexHelper.indexManager();
            IIndex index = mgr.getIndex(projects.toArray(new ICProject[0]));
            if (index == null) {
                return McpToolResult.error("CDT index is not available for the selected project(s).");
            }
            index.acquireReadLock();
            try {
                IIndexFile file = findFile(index, path);
                if (file == null) {
                    return McpToolResult.error("No indexed file matches '" + path
                            + "'. Build the project first or check the path.");
                }
                matched = displayPath(file);
                Set<String> seen = new HashSet<>();
                for (IIndexName name : file.findNames(0, Integer.MAX_VALUE)) {
                    if (total >= maxResults) {
                        break;
                    }
                    if (!name.isDefinition() && !name.isDeclaration()) {
                        continue;
                    }
                    IIndexBinding binding = index.findBinding(name);
                    if (binding == null) {
                        continue;
                    }
                    String kind = CdtIndexHelper.kindOf(binding);
                    if (!kinds.isEmpty() && !kinds.contains(kind)) {
                        continue;
                    }
                    String[] qn = binding.getQualifiedName();
                    String qualified = qn != null && qn.length > 0 ? String.join("::", qn) : binding.getName();
                    if (!seen.add(kind + ":" + qualified + ":" + CdtIndexHelper.locationLine(name))) {
                        continue;
                    }
                    JsonObject node = CdtIndexHelper.describe(name, binding, true);
                    if (maxDepth > 1 && binding instanceof IType) {
                        JsonArray fields = fieldsOf((IType) binding);
                        if (fields.size() > 0) {
                            node.put("fields", fields);
                        }
                    }
                    slots.add(new Slot(node, CdtIndexHelper.locationPath(name), name.getNodeOffset(),
                            CdtIndexHelper.locationLine(name)));
                    total++;
                }
            } finally {
                index.releaseReadLock();
            }
        } catch (CoreException | InterruptedException e) {
            return McpToolResult.error("Index query failed: " + e.getMessage());
        }

        // The index places bindings produced by a macro expansion at the macro's
        // call site, so several distinct symbols share one exact coordinate. Such
        // a coordinate is an approximation, not a definition location: mark it so
        // callers do not present it as precise, and name the macro on that line.
        Map<String, Integer> byCoordinate = new HashMap<>();
        for (Slot slot : slots) {
            byCoordinate.merge(slot.path() + ":" + slot.offset(), Integer.valueOf(1),
                    (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
        }
        JsonArray symbols = new JsonArray();
        for (Slot slot : slots) {
            Integer shared = byCoordinate.get(slot.path() + ":" + slot.offset());
            if (shared != null && shared.intValue() > 1) {
                String macro = CdtIndexHelper.macroAt(slot.path(), slot.offset());
                slot.node().put("approximateLocation", Boolean.TRUE);
                if (macro != null) {
                    slot.node().put("fromMacro", macro);
                }
            }
            symbols.add(slot.node());
        }
        JsonObject result = new JsonObject()
                .put("path", path)
                .put("matchedPath", matched)
                .put("project", project)
                .put("maxDepth", Integer.valueOf(maxDepth))
                .put("kindFilter", kinds.isEmpty() ? null : kinds)
                .put("total", Integer.valueOf(total))
                .put("truncated", Boolean.valueOf(total >= maxResults))
                .put("symbols", symbols);
        return McpToolResult.of(result.toString());
    }

    /** Direct match on the full workspace or URI path wins; a unique suffix is a fallback. */
    private static IIndexFile findFile(IIndex index, String wanted) throws CoreException {
        String normalized = normalize(wanted);
        IIndexFile suffix = null;
        for (IIndexFile file : index.getAllFiles()) {
            IIndexFileLocation location = file.getLocation();
            String full = location == null ? null : location.getFullPath();
            String uri = location != null && location.getURI() != null ? location.getURI().getPath() : null;
            if (equalsPath(full, normalized) || equalsPath(uri, normalized)) {
                return file;
            }
            if (suffix == null && (endsWithPath(full, normalized) || endsWithPath(uri, normalized))) {
                suffix = file;
            }
        }
        return suffix;
    }

    private static String normalize(String path) {
        String value = path.replace('\\', '/');
        return value.startsWith("/") ? value.substring(1) : value; //$NON-NLS-1$
    }

    private static String canonical(String path) {
        if (path == null) {
            return null;
        }
        String value = path.replace('\\', '/');
        return value.startsWith("/") ? value.substring(1) : value; //$NON-NLS-1$
    }

    private static boolean equalsPath(String candidate, String normalized) {
        String value = canonical(candidate);
        return value != null && value.equals(normalized);
    }

    private static boolean endsWithPath(String candidate, String normalized) {
        String value = canonical(candidate);
        return value != null && value.endsWith(normalized);
    }

    private static String displayPath(IIndexFile file) throws CoreException {
        IIndexFileLocation location = file.getLocation();
        if (location == null) {
            return null;
        }
        String full = location.getFullPath();
        return full != null ? full : (location.getURI() == null ? null : location.getURI().getPath());
    }

    /** STM32 headers declare structs as typedefs, so unwrap one level before asking for fields. */
    private static JsonArray fieldsOf(IType type) {
        JsonArray result = new JsonArray();
        IType resolved = type;
        if (resolved instanceof ITypedef typedef) {
            resolved = typedef.getType();
        }
        if (!(resolved instanceof ICompositeType composite)) {
            return result;
        }
        for (IField field : composite.getFields()) {
            result.add(new JsonObject().put("name", field.getName()).put("kind", "field")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    private static List<String> kinds(Map<String, Object> arguments, ToolArguments args) {
        List<String> result = new ArrayList<>();
        Object raw = arguments == null ? null : arguments.get("kindFilter"); //$NON-NLS-1$
        if (raw instanceof String single) {
            addKind(result, single);
        } else if (raw instanceof List<?> list) {
            for (Object entry : list) {
                addKind(result, String.valueOf(entry));
            }
        }
        return result;
    }

    private static void addKind(List<String> into, String value) {
        String kind = value.trim().toLowerCase(Locale.ROOT);
        if (!kind.isEmpty() && !into.contains(kind)) {
            into.add(kind);
        }
    }
}
