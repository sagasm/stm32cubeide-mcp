package com.vogella.eclipse.mcp.stm32.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunch;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reads memory-mapped peripheral registers through the suspended target's C
 * symbols, the way the IDE's SFR and Live Expressions views do.
 */
public final class SfrReadTool implements IMcpTool {

    private static final Pattern HEX = Pattern.compile("0[xX]([0-9a-fA-F]+)");

    @Override
    public String getName() {
        return "stm32_sfr_read"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Reads peripheral registers from a suspended debug session, like the IDE's SFR view. "
                + "Give 'expression' (e.g. 'GPIOA->MODER') or 'peripheral' plus 'register' "
                + "(e.g. 'GPIOA' / 'MODER'); several registers can be comma separated. Each value "
                + "is evaluated by GDB in the top frame. 'live' adds the address, a raw byte dump "
                + "from -data-read-memory-bytes and a binary view, like a Live Expressions watch. "
                + "The session must be suspended at a breakpoint so the core is stopped."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema() {
        return """
                {
                  "type": "object",
                  "required": ["sessionId"],
                  "properties": {
                    "sessionId":  {"type":"string","description":"Session id from stm32_debug_launch or stm32_debug_control action=sessions."},
                    "peripheral": {"type":"string","description":"Peripheral C symbol, e.g. GPIOA, RCC, USART1."},
                    "register":   {"type":"string","description":"Register field, e.g. MODER. Comma separated for several; a leading -> or . is accepted."},
                    "expression": {"type":"string","description":"Direct C expression, e.g. 'GPIOA->MODER'; takes precedence over peripheral/register."},
                    "live":       {"type":"boolean","default":false,"description":"Include address, raw memory bytes and a binary view for each register."},
                    "width":      {"type":"integer","default":4,"minimum":1,"maximum":16,"description":"Bytes of raw memory to read per register when live=true."},
                    "thread":     {"type":"integer","default":0,"description":"Zero-based thread index whose top frame hosts the evaluation."}
                  },
                  "additionalProperties": false
                }"""; //$NON-NLS-1$
    }

    @Override
    public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
        ToolArguments args = ToolArguments.of(arguments);
        String sessionId = args.getString("sessionId"); //$NON-NLS-1$
        if (sessionId == null) {
            return McpToolResult.error("The 'sessionId' argument is required."); //$NON-NLS-1$
        }
        ILaunch launch = DebugSessions.get(sessionId);
        if (launch == null) {
            return McpToolResult.error("No session '" + sessionId + "'. Use stm32_debug_control action=sessions to list them."); //$NON-NLS-1$
        }
        if (!(launch instanceof GdbLaunch gdbLaunch)) {
            return McpToolResult.error("Session '" + sessionId + "' is not a DSF/GDB launch; SFR reads need one."); //$NON-NLS-1$
        }
        List<String> expressions = expressions(args);
        if (expressions.isEmpty()) {
            return McpToolResult.error("Provide 'expression', or 'peripheral' together with 'register'."); //$NON-NLS-1$
        }
        boolean live = args.getBoolean("live", false); //$NON-NLS-1$
        int width = args.getInt("width", 4, 1, 16); //$NON-NLS-1$
        int thread = args.getInt("thread", 0, 0, Integer.MAX_VALUE); //$NON-NLS-1$

        JsonArray registers = new JsonArray();
        String function = null;
        String file = null;
        Object line = null;
        for (String expression : expressions) {
            JsonObject entry = new JsonObject();
            entry.put("expression", expression); //$NON-NLS-1$
            try {
                JsonObject evaluated = DebugSessions.evaluateDsf(gdbLaunch, expression, thread);
                Object value = evaluated.remove("value"); //$NON-NLS-1$
                Object success = evaluated.remove("success"); //$NON-NLS-1$
                entry.put("value", value == null ? "" : String.valueOf(value)); //$NON-NLS-1$
                entry.put("success", success); //$NON-NLS-1$
                if (function == null) {
                    function = text(evaluated.remove("function")); //$NON-NLS-1$
                    file = text(evaluated.remove("file")); //$NON-NLS-1$
                    line = evaluated.remove("line"); //$NON-NLS-1$
                } else {
                    evaluated.remove("function"); //$NON-NLS-1$
                    evaluated.remove("file"); //$NON-NLS-1$
                    evaluated.remove("line"); //$NON-NLS-1$
                }
                Object error = evaluated.remove("error"); //$NON-NLS-1$
                if (error != null) {
                    entry.put("error", error); //$NON-NLS-1$
                }
                if (live && Boolean.TRUE.equals(success)) {
                    addLiveView(gdbLaunch, entry, expression, value, width);
                }
            } catch (CoreException e) {
                entry.put("success", Boolean.FALSE); //$NON-NLS-1$
                entry.put("error", e.getMessage()); //$NON-NLS-1$
            }
            registers.add(entry);
        }

        JsonObject result = new JsonObject()
                .put("sessionId", sessionId) //$NON-NLS-1$
                .put("live", Boolean.valueOf(live)) //$NON-NLS-1$
                .put("function", function) //$NON-NLS-1$
                .put("file", file) //$NON-NLS-1$
                .put("line", line) //$NON-NLS-1$
                .put("registers", registers); //$NON-NLS-1$
        return McpToolResult.of(result.toString());
    }

    private static void addLiveView(GdbLaunch launch, JsonObject entry, String expression, Object value, int width)
            throws CoreException {
        try {
            long address = DebugSessions.resolveAddressDsf(launch, expression);
            String hexAddress = "0x" + Long.toHexString(address); //$NON-NLS-1$
            entry.put("address", hexAddress); //$NON-NLS-1$
            JsonObject raw = DebugSessions.readMemoryDsf(launch, hexAddress, 0, width);
            entry.put("rawHexdump", raw.remove("hexdump")); //$NON-NLS-1$
            entry.put("rawAscii", raw.remove("ascii")); //$NON-NLS-1$
        } catch (CoreException e) {
            entry.put("addressError", e.getMessage()); //$NON-NLS-1$
        }
        Long numeric = numeric(value);
        if (numeric != null) {
            int bits = width * 8;
            String hex = String.format("%0" + (width * 2) + "x", numeric); //$NON-NLS-1$ //$NON-NLS-2$
            String binary = Long.toBinaryString(numeric);
            if (binary.length() > bits) {
                binary = binary.substring(binary.length() - bits);
            }
            entry.put("hex", "0x" + hex); //$NON-NLS-1$
            entry.put("binary", "0".repeat(bits - binary.length()) + binary); //$NON-NLS-1$
        }
    }

    private static Long numeric(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        Matcher matcher = HEX.matcher(text);
        try {
            if (matcher.find()) {
                return Long.valueOf(Long.parseUnsignedLong(matcher.group(1), 16));
            }
            return Long.valueOf(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> expressions(ToolArguments args) {
        String direct = args.getString("expression"); //$NON-NLS-1$
        if (direct != null && !direct.isBlank()) {
            return split(direct);
        }
        String peripheral = args.getString("peripheral"); //$NON-NLS-1$
        if (peripheral == null || peripheral.isBlank()) {
            return List.of();
        }
        String base = peripheral.trim();
        String register = args.getString("register"); //$NON-NLS-1$
        if (register == null || register.isBlank()) {
            return List.of(base);
        }
        List<String> result = new ArrayList<>();
        for (String name : split(register)) {
            String field = name.trim();
            result.add(field.startsWith("->") || field.startsWith(".") //$NON-NLS-1$ //$NON-NLS-2$
                    ? base + field
                    : base + "->" + field); //$NON-NLS-1$
        }
        return result;
    }

    private static List<String> split(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) { //$NON-NLS-1$
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
