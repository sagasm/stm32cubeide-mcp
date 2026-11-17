package com.vogella.eclipse.mcp.stm32.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports what a CubeMX regeneration would overwrite, and offers a guarded
 * entry point that opens the .ioc in CubeMX for a user-reviewed regeneration.
 */
public final class RegenerateTool implements IMcpTool {

    private static final String DEFAULT_CUBEMX = "D:\\tools\\hardware\\STM32CubeMX\\STM32CubeMX.exe"; //$NON-NLS-1$
    private static final Map<String, String> LAST_DRY_RUN = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "cubeide_regenerate"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Analyzes or starts a CubeMX code regeneration for a STM32CubeIDE project. dryRun=true "
                + "(the default) is read-only: it reports the MCU, directories the generator rewrites, "
                + "USER CODE sections, Replace/ copies that survive regeneration, the .ioc fingerprint "
                + "and whether the working copy is clean. A real run needs BOTH dryRun=false and "
                + "confirm=true, a prior dryRun with the same .ioc fingerprint, and a clean working "
                + "copy; it then launches CubeMX with the .ioc open. The tool does NOT click Generate "
                + "for you: review the impact report and finish in the CubeMX GUI. See "
                + "docs/CubeMX重生成工具_约束与回滚.md."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "project":   {"type":"string","description":"Workspace project holding the .ioc."},
                    "path":      {"type":"string","description":"Direct .ioc path; overrides project."},
                    "dryRun":    {"type":"boolean","default":true,"description":"Read-only impact report (default). Set false only with confirm=true."},
                    "confirm":   {"type":"boolean","default":false,"description":"Required together with dryRun=false to launch CubeMX."},
                    "cubeMxPath":{"type":"string","description":"CubeMX executable; defaults to the installed D: path."}
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
        boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
        boolean confirm = args.getBoolean("confirm", false); //$NON-NLS-1$

        IProject project = null;
        if (projectName != null && !projectName.isBlank()) {
            project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists()) {
                return McpToolResult.error("Project '" + projectName + "' does not exist in the workspace."); //$NON-NLS-1$
            }
        }
        Path ioc = resolveIoc(project, pathArg);
        if (ioc == null) {
            return McpToolResult.error("No .ioc found; provide 'path' explicitly."); //$NON-NLS-1$
        }
        Path root = ioc.getParent();
        try {
            IocProject parsed = IocProject.read(ioc);
            String fingerprint = fingerprint(ioc);
            String gitStatus = gitStatus(root);
            JsonObject report = parsed.summary();
            JsonArray paths = new JsonArray();
            parsed.impactPaths().forEach(paths::add);
            JsonArray replace = new JsonArray();
            parsed.replaceDirs(root).forEach(replace::add);

            JsonObject result = new JsonObject()
                    .put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
                    .put("confirmed", Boolean.valueOf(confirm)) //$NON-NLS-1$
                    .put("ioc", ioc.toString()) //$NON-NLS-1$
                    .put("fingerprint", fingerprint) //$NON-NLS-1$
                    .put("gitCwd", root.toString()) //$NON-NLS-1$
                    .put("gitRoot", gitRoot(root)) //$NON-NLS-1$
                    .put("gitStatus", gitStatus) //$NON-NLS-1$
                    .put("impactPaths", paths) //$NON-NLS-1$
                    .put("replaceDirs", replace) //$NON-NLS-1$
                    .put("mcu", parsed.get("Mcu.Name")) //$NON-NLS-1$ //$NON-NLS-2$
                    .put("keepUserCode", parsed.get("ProjectManager.KeepUserCode")); //$NON-NLS-1$ //$NON-NLS-2$

            if (dryRun) {
                LAST_DRY_RUN.put(ioc.toString(), fingerprint);
                result.put("readyToRegenerate", Boolean.valueOf(confirm && gitStatus.isEmpty())); //$NON-NLS-1$
                result.put("nextStep", "Review the impact report; a real run needs dryRun=false and confirm=true."); //$NON-NLS-1$
                return McpToolResult.of(result.toString());
            }

            String rejection = guard(ioc, fingerprint, confirm, gitStatus);
            if (rejection != null) {
                return McpToolResult.error(rejection);
            }
            String cubeMxPath = args.getString("cubeMxPath", DEFAULT_CUBEMX); //$NON-NLS-1$
            Path executable = Path.of(cubeMxPath);
            if (!Files.isExecutable(executable)) {
                return McpToolResult.error("CubeMX executable not found or not executable: " + cubeMxPath); //$NON-NLS-1$
            }
            Process process = new ProcessBuilder(executable.toString(), ioc.toString())
                    .directory(root.toFile()).start();
            return McpToolResult.of(result.put("launched", Boolean.TRUE) //$NON-NLS-1$
                    .put("pid", Long.valueOf(process.pid())) //$NON-NLS-1$
                    .put("nextStep", "CubeMX is open with this .ioc. Review the changes, click Generate Code, then refresh and build in the IDE.").toString()); //$NON-NLS-1$
        } catch (IOException | NoSuchAlgorithmException e) {
            return McpToolResult.error("Regeneration analysis failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** All gates must pass before even launching CubeMX; the GUI still owns the final Generate click. */
    private static String guard(Path ioc, String fingerprint, boolean confirm, String gitStatus) {
        if (!confirm) {
            return "A real run requires confirm=true together with dryRun=false."; //$NON-NLS-1$
        }
        String previous = LAST_DRY_RUN.get(ioc.toString());
        if (previous == null) {
            return "Run dryRun=true for this .ioc first."; //$NON-NLS-1$
        }
        if (!previous.equals(fingerprint)) {
            return "The .ioc changed since the dryRun; run dryRun=true again."; //$NON-NLS-1$
        }
        if (!gitStatus.isEmpty()) {
            return "The working copy is not clean; commit or stash first. Dirty paths:\n" + gitStatus; //$NON-NLS-1$
        }
        return null;
    }

    private static Path resolveIoc(IProject project, String pathArg) {
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
        try (var stream = Files.list(project.getLocation().toFile().toPath())) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ioc")) //$NON-NLS-1$
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String fingerprint(Path ioc) throws IOException, NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(ioc)); //$NON-NLS-1$
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            result.append(String.format("%02x", Byte.valueOf(b))); //$NON-NLS-1$
        }
        return result.toString();
    }

    /** The repository toplevel git resolves from the ioc's directory, for diagnosing gitStatus. */
    private static String gitRoot(Path root) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--show-toplevel") //$NON-NLS-1$ //$NON-NLS-2$
                    .directory(root.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 ? output : "unavailable"; //$NON-NLS-1$
        } catch (IOException e) {
            return "unavailable: " + e.getMessage(); //$NON-NLS-1$
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unavailable: interrupted"; //$NON-NLS-1$
        }
    }

    private static String gitStatus(Path root) {
        try {
            Process process = new ProcessBuilder("git", "status", "--porcelain") //$NON-NLS-1$ //$NON-NLS-2$
                    .directory(root.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 ? output : "unavailable"; //$NON-NLS-1$
        } catch (IOException e) {
            return "unavailable: " + e.getMessage(); //$NON-NLS-1$
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unavailable: interrupted"; //$NON-NLS-1$
        }
    }
}
