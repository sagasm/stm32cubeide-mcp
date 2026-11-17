package com.vogella.eclipse.mcp.stm32.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunch;

import com.vogella.eclipse.mcp.core.ClientSessions;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the state of a build-and-flash operation started by stm32_build_and_flash.
 */
public final class FlashStatusTool implements IMcpTool {

	@Override
	public String getName() {
		return "stm32_flash_status"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the state of an operation started by stm32_build_and_flash: running, done or failed, its configuration/project, elapsed time, and the sessionId once the target is up. Pass operationId from that tool; omit it only when one MCP client is connected, in which case the most recent operation is reported. On 'done', use the sessionId with stm32_debug_control / stm32_debug_read_memory; the debug probe stays in use until that session is terminated."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "operationId": {"type":"string","description":"Identifier returned by stm32_build_and_flash. Omit for the most recent operation when a single client is connected."},
				    "includeSession": {"type":"boolean","default":false,"description":"Include the debug session status (state and top frame) once the operation is done."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String operationId = args.getString("operationId");
		if (operationId == null && !ClientSessions.canAssumeASingleClient()) {
			return McpToolResult.error(ClientSessions.ambiguousDefault("flash operation", //$NON-NLS-1$
					"operationId", FlashRegistry.getInstance().ids())); //$NON-NLS-1$
		}
		FlashRegistry.Flash flash = operationId == null ? FlashRegistry.getInstance().findLatest()
				: FlashRegistry.getInstance().find(operationId);
		if (flash == null) {
			if (operationId != null) {
				return McpToolResult.error("No flash operation with id '" + operationId + "'. Only the last few operations are kept."); //$NON-NLS-1$
			}
			return McpToolResult.of(new JsonObject().put("state", "none") //$NON-NLS-1$ //$NON-NLS-2$
					.put("message", "No build-and-flash operation has been started yet.").toString()); //$NON-NLS-1$
		}
		JsonObject json = new JsonObject()
				.put("operationId", flash.id())
				.put("state", flash.state())
				.put("configuration", flash.configuration())
				.put("project", flash.project())
				.put("elapsedMillis", flash.elapsedMillis())
				.put("note", flash.note())
				.put("error", flash.error());
		if (flash.sessionId() != null && args.getBoolean("includeSession", false)) {
			ILaunch launch = DebugSessions.get(flash.sessionId());
			if (launch != null) {
				json.put("session", DebugSessions.describe(launch, flash.sessionId()));
			}
		}
		return McpToolResult.of(json.toString());
	}
}
