package com.vogella.eclipse.mcp.stm32.internal;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * A read-only view of a CubeMX .ioc file. The format is a line-oriented
 * key=value property file; the generator keeps MCU identity and peripheral
 * instances in a few well-known keys, which is all the info tool needs.
 */
final class IocProject {

    private static final Pattern USER_CODE = Pattern.compile("USER CODE BEGIN\\s+([A-Za-z0-9_]+)");
    private static final Charset[] ENCODINGS = { StandardCharsets.UTF_8, Charset.forName("GBK") };

    private final Path path;
    private final Map<String, String> properties;
    private final String text;

    private IocProject(Path path, Map<String, String> properties, String text) {
        this.path = path;
        this.properties = properties;
        this.text = text;
    }

    /** Parses an .ioc, trying UTF-8 then GBK (CubeMX on a Chinese locale may write either). */
    static IocProject read(Path path) throws IOException {
        String text = null;
        IOException failure = null;
        for (Charset charset : ENCODINGS) {
            try {
                text = Files.readString(path, charset);
                break;
            } catch (IOException e) {
                failure = e;
            }
        }
        if (text == null) {
            throw failure != null ? failure : new IOException("Cannot read " + path);
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals > 0) {
                properties.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
            }
        }
        return new IocProject(path, properties, text);
    }

    Path path() {
        return path;
    }

    String get(String key) {
        return properties.get(key);
    }

    /** The 'Mcu.IP0..IPn' entries, in numeric order, plus any 'Mcu.IP=' aliases. */
    List<String> peripherals() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey().matches("Mcu\\.IP\\d+") && !entry.getValue().isEmpty()) {
                result.add(entry.getValue());
            } else if ("Mcu.IP".equals(entry.getKey()) && !entry.getValue().isEmpty()) {
                result.add(entry.getValue());
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /** Distinct USER CODE BEGIN sections in the generated Core sources, in file order.
     *  The markers live in the generated .c/.h files, not in the .ioc itself. */
    List<String> userCodeSections() {
        List<String> result = new ArrayList<>();
        Path root = path.getParent();
        if (root == null) {
            return result;
        }
        for (String folderName : new String[] { "Core/Src", "Core/Inc" }) { //$NON-NLS-1$ //$NON-NLS-2$
            Path folder = root.resolve(folderName);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (var stream = Files.walk(folder)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".c") || p.toString().endsWith(".h")) //$NON-NLS-1$ //$NON-NLS-2$
                        .toList()) {
                    // ISO-8859-1 preserves the ASCII marker bytes whatever the file encoding is.
                    Matcher matcher = USER_CODE.matcher(Files.readString(file, StandardCharsets.ISO_8859_1));
                    while (matcher.find()) {
                        String section = matcher.group(1);
                        if (!result.contains(section)) {
                            result.add(section);
                        }
                    }
                }
            } catch (IOException e) {
                // an unreadable generated file just contributes no sections
            }
        }
        return result;
    }

    /** Full MCU / clock / project summary used by cubeide_project_info. */
    JsonObject summary() {
        JsonArray ips = new JsonArray();
        peripherals().forEach(ips::add);
        JsonArray sections = new JsonArray();
        userCodeSections().forEach(sections::add);
        return new JsonObject()
                .put("path", path.toString())
                .put("fileName", path.getFileName().toString())
                .put("mcu", get("Mcu.Name"))
                .put("cpn", get("Mcu.CPN"))
                .put("family", get("Mcu.Family"))
                .put("package", get("Mcu.Package"))
                .put("deviceId", get("ProjectManager.DeviceId"))
                .put("firmwarePackage", get("ProjectManager.FirmwarePackage"))
                .put("toolchain", get("ProjectManager.TargetToolchain"))
                .put("projectName", get("ProjectManager.ProjectName"))
                .put("mainLocation", get("ProjectManager.MainLocation"))
                .put("hseValue", get("RCC.HSE_VALUE"))
                .put("heapSize", get("ProjectManager.HeapSize"))
                .put("stackSize", get("ProjectManager.StackSize"))
                .put("keepUserCode", get("ProjectManager.KeepUserCode"))
                .put("peripherals", ips)
                .put("userCodeSections", sections)
                .put("userCodeSectionCount", Integer.valueOf(sections.size()))
                .put("propertyCount", Integer.valueOf(properties.size()));
    }

    /** The generator-owned paths a regeneration may rewrite. */
    List<String> impactPaths() {
        return List.of("Core/Inc", "Core/Src", "Drivers", "USB_HOST", "Middlewares", "FATFS", "LWIP");
    }

    /** All Replace directories below a root, as paths relative to that root. */
    List<String> replaceDirs(Path root) {
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isDirectory)
                    .filter(dir -> "Replace".equals(dir.getFileName().toString()))
                    .forEach(dir -> result.add(root.relativize(dir).toString().replace('\\', '/')));
        } catch (IOException e) {
            return Collections.emptyList();
        }
        Collections.sort(result);
        return result;
    }
}
