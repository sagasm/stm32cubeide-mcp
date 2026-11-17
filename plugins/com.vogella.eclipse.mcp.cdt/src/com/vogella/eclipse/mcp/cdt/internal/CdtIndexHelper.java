package com.vogella.eclipse.mcp.cdt.internal;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.dom.ast.IType;
import org.eclipse.cdt.core.dom.ast.IFunction;
import org.eclipse.cdt.core.dom.ast.IMacroBinding;
import org.eclipse.cdt.core.dom.ast.IVariable;
import org.eclipse.cdt.core.index.IIndexBinding;
import org.eclipse.cdt.core.index.IIndexFileLocation;
import org.eclipse.cdt.core.index.IIndexManager;
import org.eclipse.cdt.core.index.IIndexName;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;

/** Helper around the CDT PDOM index used by the CDT MCP tools. */
final class CdtIndexHelper {

    private CdtIndexHelper() {
    }

    /** Resolves a project name to its {@link ICProject}, or all C/C++ projects when name is blank. */
    static List<ICProject> resolveProjects(String projectName) throws CoreException {
        List<ICProject> result = new ArrayList<>();
        CoreModel model = CoreModel.getDefault();
        if (projectName != null && !projectName.isBlank()) {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (p != null && p.exists() && model.hasCNature(p)) {
                ICProject c = model.create(p);
                if (c != null) {
                    result.add(c);
                }
            }
            return result;
        }
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (p.isAccessible() && model.hasCNature(p)) {
                ICProject c = model.create(p);
                if (c != null) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    static IIndexManager indexManager() {
        return CCorePlugin.getIndexManager();
    }

    /** Classifies a binding for human-readable output. */
    static String kindOf(IIndexBinding binding) {
        if (binding instanceof IFunction) {
            return "function";
        }
        if (binding instanceof IVariable) {
            return "variable";
        }
        if (binding instanceof IType) {
            return "type";
        }
        if (binding instanceof IMacroBinding) {
            return "macro";
        }
        return "other";
    }

    /** Describes one index occurrence (definition or reference) as a JSON object. */
    static com.vogella.eclipse.mcp.core.json.JsonObject describe(
            IIndexName name, IIndexBinding binding, boolean isDefinition) {
        com.vogella.eclipse.mcp.core.json.JsonObject o = new com.vogella.eclipse.mcp.core.json.JsonObject();
        o.put("name", binding.getName());
        String[] qn = binding.getQualifiedName();
        o.put("qualifiedName", qn != null && qn.length > 0 ? String.join("::", qn) : binding.getName());
        o.put("kind", kindOf(binding));
        o.put("isDefinition", isDefinition);
        IIndexFileLocation loc = null;
        try {
            loc = name.getFile().getLocation();
        } catch (CoreException e) {
            loc = null;
        }
        String path = "";
        if (loc != null) {
            path = loc.getFullPath();
            if (path == null && loc.getURI() != null) {
                path = loc.getURI().getPath();
            }
        }
        o.put("path", path);
        int offset = name.getNodeOffset();
        o.put("offset", offset);
        if (path != null && !path.isEmpty()) {
            int line = lineOf(path, offset);
            if (line > 0) {
                o.put("line", line);
            }
        }
        return o;
    }

    /** Like {@link #describe(IIndexName, IIndexBinding, boolean)} but additionally includes a
     *  source snippet around the definition (only when the file can be read). */
    static com.vogella.eclipse.mcp.core.json.JsonObject describeWithSnippet(
            IIndexName name, IIndexBinding binding, int contextLines) {
        com.vogella.eclipse.mcp.core.json.JsonObject o = describe(name, binding, true);
        IIndexFileLocation loc = null;
        try {
            loc = name.getFile().getLocation();
        } catch (CoreException e) {
            loc = null;
        }
        String path = "";
        if (loc != null) {
            path = loc.getFullPath();
            if (path == null && loc.getURI() != null) {
                path = loc.getURI().getPath();
            }
        }
        int offset = name.getNodeOffset();
        int line = lineOf(path, offset);
        if (line > 0) {
            String snippet = readLinesAround(path, line, contextLines);
            if (!snippet.isEmpty()) {
                o.put("snippet", snippet);
            }
        }
        return o;
    }

    /** Returns the file path for an index occurrence, or empty string if unknown. */
    static String locationPath(IIndexName name) {
        IIndexFileLocation loc = null;
        try {
            loc = name.getFile().getLocation();
        } catch (CoreException e) {
            loc = null;
        }
        if (loc != null) {
            String p = loc.getFullPath();
            if (p == null && loc.getURI() != null) {
                p = loc.getURI().getPath();
            }
            return p == null ? "" : p;
        }
        return "";
    }

    /** Returns the 1-based line for an index occurrence, or -1 if unknown. */
    static int locationLine(IIndexName name) {
        String p = locationPath(name);
        return p.isEmpty() ? -1 : lineOf(p, name.getNodeOffset());
    }

    /** Computes a 1-based line number for an offset within a file, or -1 if unknown. */
    static int lineOf(String path, int offset) {
        String content = readText(path);
        if (content == null) {
            return -1;
        }
        int line = 1;
        int max = Math.min(offset, content.length());
        for (int i = 0; i < max; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Reads a workspace or filesystem file as text, or null on failure. */
    static String readText(String path) {
        try {
            IFile f = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(path));
            if (f != null && f.exists()) {
                try (InputStream in = f.getContents()) {
                    return new String(in.readAllBytes(), f.getCharset());
                }
            }
            // Fallback for linked resources (workspace-relative paths).
            org.eclipse.core.resources.IResource member =
                    ResourcesPlugin.getWorkspace().getRoot().findMember(new Path(path));
            if (member instanceof IFile) {
                IFile mf = (IFile) member;
                if (mf.exists()) {
                    try (InputStream in = mf.getContents()) {
                        return new String(in.readAllBytes(), mf.getCharset());
                    }
                }
            }
            java.io.File file = new java.io.File(path);
            if (file.exists()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** Reads one source line, without a line-number prefix, for a reference hit. */
    static String lineText(String path, int line) {
        String content = readText(path);
        if (content == null || line < 1) {
            return null;
        }
        String[] lines = content.split("\n", -1);
        if (line > lines.length) {
            return null;
        }
        String text = lines[line - 1];
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    /** Reads `context` lines around a 1-based `centerLine`, each prefixed with its line number. */
    static String readLinesAround(String path, int centerLine, int context) {
        String content = readText(path);
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        int n = lines.length;
        int from = Math.max(1, centerLine - context);
        int to = Math.min(n, centerLine + context);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(i).append(": ").append(lines[i - 1]).append("\n");
        }
        return sb.toString();
    }
}
