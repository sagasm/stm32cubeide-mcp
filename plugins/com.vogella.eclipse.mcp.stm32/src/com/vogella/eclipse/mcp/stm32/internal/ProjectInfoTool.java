package com.vogella.eclipse.mcp.stm32.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IProject;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Read-only view of a CubeIDE project's MCU, clock and peripheral configuration,
 * taken from its .ioc and its active CDT build configuration.
 */
public final class ProjectInfoTool implements IMcpTool {

    @Override
    public String getName() {
        return "cubeide_project_info"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Reports MCU identity, firmware package, clock source, enabled peripherals and the active "
                + "build configuration of a STM32CubeIDE project. Reads the project's .ioc (INI style) "
                + "and CDT managed-build metadata; it never writes or regenerates anything. Use 'project' "
                + "to pick a workspace project, or 'path' to read an .ioc directly. Remember the .ioc is "
                + "the nominal configuration: the actual silicon on this user's KZ036 board is a GD32F450, "
                + "so do not assume register layouts from the MCU name alone."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "project": {"type":"string","description":"Workspace project name holding the .ioc."},
                    "path":    {"type":"string","description":"Direct .ioc path, absolute or workspace relative; overrides project."}
                  },
                  "additionalProperties": false
                }"""; //$NON-NLS-1$
    }

    @Override
    public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
        ToolArguments args = ToolArguments.of(arguments);
        String projectName = args.getString("project"); //$NON-NLS-1$
        String pathArg = args.getString("path"); //$NON-NLS-1$
        if ((projectName == null || projectName.isBlank()) && (pathArg == null || pathArg.isBlank())) {
            return McpToolResult.error("Provide 'project' or 'path'."); //$NON-NLS-1$
        }

        IProject workspaceProject = null;
        if (projectName != null && !projectName.isBlank()) {
            workspaceProject = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!workspaceProject.exists()) {
                return McpToolResult.error("Project '" + projectName + "' does not exist in the workspace."); //$NON-NLS-1$
            }
        }

        Path ioc;
        try {
            ioc = resolveIoc(workspaceProject, pathArg);
        } catch (IOException e) {
            return McpToolResult.error("Locating the .ioc failed: " + e.getMessage()); //$NON-NLS-1$
        }
        if (ioc == null) {
            return McpToolResult.error("No .ioc found in project '" + projectName + "'."); //$NON-NLS-1$
        }
        try {
            IocProject parsed = IocProject.read(ioc);
            JsonObject result = parsed.summary();
            if (workspaceProject != null) {
                result.put("workspaceProject", workspaceProject.getName()); //$NON-NLS-1$
                result.put("buildConfig", buildConfig(workspaceProject)); //$NON-NLS-1$
            } else {
                result.put("workspaceProject", null); //$NON-NLS-1$
                result.put("buildConfig", null); //$NON-NLS-1$
            }
            return McpToolResult.of(result.toString());
        } catch (IOException e) {
            return McpToolResult.error("Reading '" + ioc + "' failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static Path resolveIoc(IProject project, String pathArg) throws IOException {
        if (pathArg != null && !pathArg.isBlank()) {
            Path candidate = Path.of(pathArg);
            if (!candidate.isAbsolute() && project != null && project.getLocation() != null) {
                candidate = project.getLocation().toFile().toPath().resolve(candidate);
            } else if (!candidate.isAbsolute()) {
                candidate = Path.of("").toAbsolutePath().resolve(candidate); //$NON-NLS-1$
            }
            return Files.exists(candidate) ? candidate : null;
        }
        if (project == null || project.getLocation() == null) {
            return null;
        }
        Path root = project.getLocation().toFile().toPath();
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ioc")) //$NON-NLS-1$
                    .findFirst().orElse(null);
        }
    }

    /** CDT marks ICProjectDescription non-API, so read the configuration names straight from .cproject. */
    private static JsonObject buildConfig(IProject project) {
        JsonObject result = new JsonObject();
        if (project.getLocation() == null) {
            result.put("available", Boolean.FALSE); //$NON-NLS-1$
            return result;
        }
        Path cproject = project.getLocation().toFile().toPath().resolve(".cproject"); //$NON-NLS-1$
        if (!Files.isRegularFile(cproject)) {
            result.put("available", Boolean.FALSE); //$NON-NLS-1$
            return result;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            NodeList elements = factory.newDocumentBuilder().parse(cproject.toFile())
                    .getElementsByTagName("configuration"); //$NON-NLS-1$
            List<String> names = new ArrayList<>();
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                String name = element.getAttribute("name"); //$NON-NLS-1$
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
            JsonArray array = new JsonArray();
            names.forEach(array::add);
            return result.put("available", Boolean.TRUE) //$NON-NLS-1$
                    .put("source", ".cproject") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("active", names.isEmpty() ? null : names.get(0)) //$NON-NLS-1$
                    .put("names", array); //$NON-NLS-1$
        } catch (Exception e) {
            return result.put("available", Boolean.FALSE).put("error", e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
