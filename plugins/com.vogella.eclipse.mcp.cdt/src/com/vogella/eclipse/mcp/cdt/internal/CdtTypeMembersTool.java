package com.vogella.eclipse.mcp.cdt.internal;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.IArrayType;
import org.eclipse.cdt.core.dom.ast.ICompositeType;
import org.eclipse.cdt.core.dom.ast.IEnumeration;
import org.eclipse.cdt.core.dom.ast.IEnumerator;
import org.eclipse.cdt.core.dom.ast.IField;
import org.eclipse.cdt.core.dom.ast.IPointerType;
import org.eclipse.cdt.core.dom.ast.IQualifierType;
import org.eclipse.cdt.core.dom.ast.IType;
import org.eclipse.cdt.core.dom.ast.ITypedef;
import org.eclipse.cdt.core.dom.ast.IVariable;
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
 * Lists the members of a C/C++ type (struct / union / class fields, or enum
 * enumerators) as found in the CDT index. Helps an MCP client understand the
 * shape of a type (e.g. a large STM32 {@code *_TypeDef} struct) without opening
 * its header.
 */
public final class CdtTypeMembersTool implements IMcpTool {

    @Override
    public String getName() {
        return "cdt_type_members";
    }

    @Override
    public String getDescription() {
        return "Lists the members of a C/C++ type found in the CDT index: the fields of a struct/union/class, "
                + "or the enumerators of an enum. For each member it returns the name, the member kind, the "
                + "declared type and the definition location (file and line). Use this to understand the shape "
                + "of a type such as an STM32 HAL handle or register struct. Optionally scoped to a project.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name":          {"type":"string","description":"Substring of the type name to inspect."},
                    "project":       {"type":"string","description":"Optional C/C++ project name to scope the search."},
                    "caseSensitive": {"type":"boolean","default":true},
                    "maxTypes":      {"type":"integer","default":20,"minimum":1,"maximum":200},
                    "maxMembers":    {"type":"integer","default":200,"minimum":1,"maximum":2000}
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
        int maxTypes = args.getInt("maxTypes", 20, 1, 200);
        int maxMembers = args.getInt("maxMembers", 200, 1, 2000);

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

        JsonArray types = new JsonArray();
        int typeCount = 0;
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
                    if (typeCount >= maxTypes) {
                        break;
                    }
                    ICompositeType ct = asComposite(b);
                    if (ct != null) {
                        types.add(describeType(index, b, ct, maxMembers));
                        typeCount++;
                        continue;
                    }
                    IEnumeration en = asEnum(b);
                    if (en != null) {
                        types.add(describeEnum(index, b, en, maxMembers));
                        typeCount++;
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
                .put("total", typeCount)
                .put("types", types);
        return McpToolResult.of(result.toString());
    }

    private static ICompositeType asComposite(IIndexBinding b) {
        if (b instanceof ITypedef) {
            IType t = ((ITypedef) b).getType();
            return t instanceof ICompositeType ? (ICompositeType) t : null;
        }
        return b instanceof ICompositeType ? (ICompositeType) b : null;
    }

    private static IEnumeration asEnum(IIndexBinding b) {
        if (b instanceof ITypedef) {
            IType t = ((ITypedef) b).getType();
            return t instanceof IEnumeration ? (IEnumeration) t : null;
        }
        return b instanceof IEnumeration ? (IEnumeration) b : null;
    }

    private static JsonObject describeType(IIndex index, IIndexBinding b, ICompositeType type, int maxMembers)
            throws CoreException {
        JsonObject o = new JsonObject();
        o.put("name", b.getName());
        o.put("qualifiedName", String.join("::", b.getQualifiedName()));
        String kind = "class";
        if (type.getKey() == ICompositeType.k_struct) {
            kind = "struct";
        } else if (type.getKey() == ICompositeType.k_union) {
            kind = "union";
        }
        o.put("kind", kind);
        JsonArray members = new JsonArray();
        int n = 0;
        for (IField f : type.getFields()) {
            if (n >= maxMembers) {
                break;
            }
            members.add(describeMember(index, f, "field", typeName(f.getType())));
            n++;
        }
        o.put("members", members);
        return o;
    }

    private static JsonObject describeEnum(IIndex index, IIndexBinding b, IEnumeration enm, int maxMembers)
            throws CoreException {
        JsonObject o = new JsonObject();
        o.put("name", b.getName());
        o.put("qualifiedName", String.join("::", b.getQualifiedName()));
        o.put("kind", "enum");
        JsonArray members = new JsonArray();
        int n = 0;
        for (IEnumerator e : enm.getEnumerators()) {
            if (n >= maxMembers) {
                break;
            }
            String value = e.getValue() != null ? e.getValue().toString() : "";
            members.add(describeMember(index, e, "enumerator", value));
            n++;
        }
        o.put("members", members);
        return o;
    }

    private static JsonObject describeMember(IIndex index, IBinding member, String memberKind, String typeOrValue)
            throws CoreException {
        JsonObject o = new JsonObject();
        o.put("name", member.getName());
        o.put("memberKind", memberKind);
        if (typeOrValue != null && !typeOrValue.isEmpty()) {
            o.put(memberKind.equals("enumerator") ? "value" : "type", typeOrValue);
        }
        if (member instanceof IIndexBinding) {
            IIndexName[] defs = index.findDefinitions((IIndexBinding) member);
            if (defs.length > 0) {
                String p = CdtIndexHelper.locationPath(defs[0]);
                int line = CdtIndexHelper.locationLine(defs[0]);
                if (!p.isEmpty()) {
                    o.put("path", p);
                }
                if (line > 0) {
                    o.put("line", line);
                }
            }
        }
        return o;
    }

    private static String typeName(IType t) {
        if (t == null) {
            return "";
        }
        if (t instanceof IBinding) {
            return ((IBinding) t).getName();
        }
        if (t instanceof IQualifierType) {
            IQualifierType q = (IQualifierType) t;
            StringBuilder sb = new StringBuilder();
            if (q.isConst()) {
                sb.append("const ");
            }
            if (q.isVolatile()) {
                sb.append("volatile ");
            }
            return sb.append(typeName(q.getType())).toString();
        }
        if (t instanceof IPointerType) {
            return typeName(((IPointerType) t).getType()) + " *";
        }
        if (t instanceof IArrayType) {
            return typeName(((IArrayType) t).getType()) + "[]";
        }
        return t.toString();
    }
}
