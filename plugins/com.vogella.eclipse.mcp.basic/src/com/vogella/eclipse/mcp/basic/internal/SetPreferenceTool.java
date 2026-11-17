package com.vogella.eclipse.mcp.basic.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.osgi.service.prefs.BackingStoreException;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.UiDispatch;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Writes a single preference and reports what was there before.
 * <p>
 * There is no qualifier allowlist, since other tools of this server write any qualifier anyway.
 */
public final class SetPreferenceTool implements IMcpTool {

	private static final String AUTOBUILD_QUALIFIER = "org.eclipse.core.resources"; //$NON-NLS-1$

	private static final String AUTOBUILD_KEY = "description.autobuilding"; //$NON-NLS-1$

	private static final String THEME_QUALIFIER = "org.eclipse.e4.ui.css.swt.theme"; //$NON-NLS-1$

	private static final String THEMEID_KEY = "themeid"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_set_preference"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Writes preferences in the instance or project scope and returns every previous value, so the change can be undone. MODIFIES THE IDE CONFIGURATION: any qualifier may be written, and a wrong preference is both invisible and long-lived, so keep the returned previous values, since nothing else records them. Writes go to the standard Eclipse scope nodes, the same ones the Window > Preferences > General > MCP Server page uses, so a change here shows up there. Pass key/value for one write, or values as a {key: value} object for a batch, where a null value removes that key; the batch answer lists every key's previous value and effective value for rollback. Auto-build (org.eclipse.core.resources, description.autobuilding) is applied through the workspace description rather than as a raw preference write, because writing the raw key does not take effect properly, so it must be written one at a time. The key themeid under org.eclipse.e4.ui.css.swt.theme is refused: the write reaches disk, but at the next startup the engine resolves the persisted id against the registered themes and an id that does not resolve is replaced by a fallback that is persisted over it, so it is not a way to switch themes. Use eclipse_set_theme."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "qualifier": {"type":"string","description":"Preference qualifier, such as org.eclipse.jdt.core. eclipse_get_preferences reads them, which is how to find the key you mean before writing it."},
				    "key":       {"type":"string","description":"Preference key for a single write."},
				    "value":     {"type":"string","description":"New value for a single write. Omit to remove the key, which lets the value below it in the lookup order take over."},
				    "values":    {"type":"object","additionalProperties":{"type":["string","null"]},"description":"Write several keys at once as {key: value}; a null value removes that key. Cannot be combined with 'key'/'value'. Auto-build and themeid must be written one at a time."},
				    "scope":     {"type":"string","enum":["instance","project"],"default":"instance"},
				    "project":   {"type":"string","description":"Project name, required for the project scope."}
				  },
				  "required": ["qualifier"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String qualifier = args.getString("qualifier"); //$NON-NLS-1$
		if (qualifier == null) {
			return McpToolResult.error("The argument 'qualifier' is required."); //$NON-NLS-1$
		}
		Map<String, String> batch = valuesOf(arguments == null ? null : arguments.get("values")); //$NON-NLS-1$
		String key = args.getString("key"); //$NON-NLS-1$
		if (batch == null && key == null) {
			return McpToolResult.error("Provide 'key' for a single write or 'values' for a batch."); //$NON-NLS-1$
		}
		if (batch != null && key != null) {
			return McpToolResult.error("'key' and 'values' cannot be combined; use one write form."); //$NON-NLS-1$
		}
		if (THEME_QUALIFIER.equals(qualifier)
				&& (THEMEID_KEY.equals(key) || (batch != null && batch.containsKey(THEMEID_KEY)))) {
			return McpToolResult.error(
					"Writing %s/%s is refused: the write reaches disk, but at the next startup the theme engine resolves the persisted id against the registered themes, and an id that does not resolve is replaced by a fallback that is persisted over it. Writing it is not a way to switch themes; use eclipse_set_theme instead."
							.formatted(THEME_QUALIFIER, THEMEID_KEY)); //$NON-NLS-1$
		}
		String scope = args.getString("scope", "instance"); //$NON-NLS-1$ //$NON-NLS-2$
		String projectName = args.getString("project"); //$NON-NLS-1$
		IScopeContext context = PreferenceScopes.writableContext(scope, projectName);
		if (context == null) {
			return McpToolResult.error("project".equals(scope) && projectName == null //$NON-NLS-1$
					? "The scope 'project' needs a 'project' argument." //$NON-NLS-1$
					: "Cannot write the scope '%s' for project '%s'.".formatted(scope, projectName)); //$NON-NLS-1$
		}
		String value = args.getString("value"); //$NON-NLS-1$
		if (batch != null) {
			if (AUTOBUILD_QUALIFIER.equals(qualifier) && batch.containsKey(AUTOBUILD_KEY)) {
				return McpToolResult
						.error("Auto-build must be written one at a time with 'key'/'value', not in a batch."); //$NON-NLS-1$
			}
			return writeBatch(qualifier, scope, projectName, context, batch);
		}

		IEclipsePreferences node = context.getNode(qualifier);
		String previous = node.get(key, null);

		if (AUTOBUILD_QUALIFIER.equals(qualifier) && AUTOBUILD_KEY.equals(key)) {
			return setAutoBuilding(value, previous);
		}
		// the write fires its listeners synchronously, and editors answer a change
		// by touching widgets, so it has to happen on the UI thread when there is one
		try {
			UiDispatch.call(() -> {
				if (value == null) {
					node.remove(key);
				} else {
					node.put(key, value);
				}
				node.flush();
				return null;
			}, CallBudget.maxWaitSeconds());
		} catch (TimeoutException e) {
			return McpToolResult.error(
					"The Eclipse UI did not process the write of %s/%s within %d seconds. It is still queued and may land later; read it back with eclipse_get_preferences." //$NON-NLS-1$
							.formatted(qualifier, key, Integer.valueOf(CallBudget.maxWaitSeconds())));
		} catch (BackingStoreException e) {
			throw new McpToolException("Could not save the preference %s/%s".formatted(qualifier, key), e); //$NON-NLS-1$
		} catch (Exception e) {
			throw new McpToolException("Could not write the preference %s/%s".formatted(qualifier, key), e); //$NON-NLS-1$
		}
		Map<String, IEclipsePreferences> nodes = PreferenceScopes.nodes(qualifier, projectName);
		String effective = null;
		String effectiveScope = null;
		for (String candidate : PreferenceScopes.LOOKUP_ORDER) {
			IEclipsePreferences lookup = nodes.get(candidate);
			String found = lookup == null ? null : lookup.get(key, null);
			if (found != null) {
				effective = found;
				effectiveScope = candidate;
				break;
			}
		}
		return McpToolResult.of(new JsonObject().put("qualifier", qualifier) //$NON-NLS-1$
				.put("key", key) //$NON-NLS-1$
				.put("scope", scope) //$NON-NLS-1$
				.put("project", projectName) //$NON-NLS-1$
				.put("previousValue", previous) //$NON-NLS-1$
				.put("effective", effective) //$NON-NLS-1$
				.put("effectiveScope", effectiveScope) //$NON-NLS-1$
				.toString());
	}

	/**
	 * Auto-build lives in the workspace description. Writing the raw preference key
	 * is the common way to get this subtly wrong, so it is special cased.
	 */
	private static McpToolResult setAutoBuilding(String value, String previous) throws McpToolException {
		if (value == null) {
			return McpToolResult.error("Auto-build cannot be removed, only set to true or false."); //$NON-NLS-1$
		}
		if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("Auto-build takes 'true' or 'false', not '%s'.".formatted(value)); //$NON-NLS-1$
		}
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		boolean was = workspace.isAutoBuilding();
		IWorkspaceDescription description = workspace.getDescription();
		description.setAutoBuilding(Boolean.parseBoolean(value));
		try {
			workspace.setDescription(description);
		} catch (CoreException e) {
			throw new McpToolException("Could not change auto-build", e); //$NON-NLS-1$
		}
		return McpToolResult.of(new JsonObject().put("qualifier", AUTOBUILD_QUALIFIER) //$NON-NLS-1$
				.put("key", AUTOBUILD_KEY) //$NON-NLS-1$
				.put("scope", "instance") //$NON-NLS-1$ //$NON-NLS-2$
				.put("appliedThrough", "IWorkspaceDescription.setAutoBuilding") //$NON-NLS-1$ //$NON-NLS-2$
				.put("previousValue", previous == null ? String.valueOf(was) : previous) //$NON-NLS-1$
				.put("effective", String.valueOf(workspace.isAutoBuilding())) //$NON-NLS-1$
				.put("effectiveScope", "instance") //$NON-NLS-1$ //$NON-NLS-2$
				.toString());
	}

	/** Converts the values argument into an ordered key/value map; null values mean "remove". */
	private static Map<String, String> valuesOf(Object raw) {
		if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
			return null;
		}
		Map<String, String> values = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String name = entry.getKey() == null ? null : String.valueOf(entry.getKey()).strip();
			if (name == null || name.isEmpty()) {
				continue;
			}
			Object supplied = entry.getValue();
			values.put(name, supplied == null ? null : String.valueOf(supplied));
		}
		return values.isEmpty() ? null : values;
	}

	/**
	 * Writes all keys in one UI dispatch and one flush, after reading every previous
	 * value: a caller can put the whole workspace back without one call per key.
	 */
	private static McpToolResult writeBatch(String qualifier, String scope, String projectName,
			IScopeContext context, Map<String, String> batch) throws McpToolException {
		IEclipsePreferences node = context.getNode(qualifier);
		Map<String, String> previousValues = new LinkedHashMap<>();
		for (String name : batch.keySet()) {
			previousValues.put(name, node.get(name, null));
		}
		try {
			UiDispatch.call(() -> {
				for (Map.Entry<String, String> change : batch.entrySet()) {
					if (change.getValue() == null) {
						node.remove(change.getKey());
					} else {
						node.put(change.getKey(), change.getValue());
					}
				}
				node.flush();
				return null;
			}, CallBudget.maxWaitSeconds());
		} catch (TimeoutException e) {
			return McpToolResult
					.error("The Eclipse UI did not process the batch write of %s within %d seconds. It is still queued and may land later; read it back with eclipse_get_preferences." //$NON-NLS-1$
							.formatted(qualifier, Integer.valueOf(CallBudget.maxWaitSeconds())));
		} catch (BackingStoreException e) {
			throw new McpToolException("Could not save the preferences of " + qualifier, e); //$NON-NLS-1$
		} catch (Exception e) {
			throw new McpToolException("Could not write the preferences of " + qualifier, e); //$NON-NLS-1$
		}
		Map<String, IEclipsePreferences> nodes = PreferenceScopes.nodes(qualifier, projectName);
		JsonArray changes = new JsonArray();
		for (Map.Entry<String, String> change : batch.entrySet()) {
			String effective = null;
			String effectiveScope = null;
			for (String candidate : PreferenceScopes.LOOKUP_ORDER) {
				IEclipsePreferences lookup = nodes.get(candidate);
				String found = lookup == null ? null : lookup.get(change.getKey(), null);
				if (found != null) {
					effective = found;
					effectiveScope = candidate;
					break;
				}
			}
			changes.add(new JsonObject().put("key", change.getKey()) //$NON-NLS-1$
					.put("previousValue", previousValues.get(change.getKey())) //$NON-NLS-1$
					.put("newValue", change.getValue()) //$NON-NLS-1$
					.put("effective", effective) //$NON-NLS-1$
					.put("effectiveScope", effectiveScope)); //$NON-NLS-1$
		}
		JsonObject previous = new JsonObject();
		previousValues.forEach(previous::put);
		return McpToolResult.of(new JsonObject().put("qualifier", qualifier) //$NON-NLS-1$
				.put("scope", scope) //$NON-NLS-1$
				.put("project", projectName) //$NON-NLS-1$
				.put("batch", Boolean.TRUE) //$NON-NLS-1$
				.put("previousValues", previous) //$NON-NLS-1$
				.put("changes", changes) //$NON-NLS-1$
				.toString());
	}
}
