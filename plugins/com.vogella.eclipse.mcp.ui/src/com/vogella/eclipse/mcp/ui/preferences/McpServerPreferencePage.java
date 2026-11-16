package com.vogella.eclipse.mcp.ui.preferences;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import com.vogella.eclipse.mcp.server.McpEndpoint;
import com.vogella.eclipse.mcp.server.McpPreferences;
import com.vogella.eclipse.mcp.server.McpServerException;
import com.vogella.eclipse.mcp.server.McpServerLifecycle;
import com.vogella.eclipse.mcp.server.McpServerService;

/**
 * Preferences UI for the embedded MCP server. It writes into the very same
 * instance-scope preference node the server reads, so toggling the server or
 * changing its port takes effect as soon as Apply or OK is pressed. It also
 * shows the bearer token and how to connect an MCP client to the endpoint.
 */
public final class McpServerPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private Text tokenField;

    private Text instructions;

    public McpServerPreferencePage() {
        super(GRID);
        ScopedPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, McpPreferences.QUALIFIER);
        // ScopedPreferenceStore reports 0 for a key that is neither set in the
        // instance scope nor present in the default scope. The server declares
        // callTimeoutSeconds / uiWaitSeconds defaults in code only, and
        // plugin_customization.ini sets just enabled and port, so seed the store
        // with the effective values the server actually uses.
        store.setDefault(McpPreferences.KEY_ENABLED, effectiveBoolean(McpPreferences.KEY_ENABLED,
                McpPreferences.DEFAULT_ENABLED));
        store.setDefault(McpPreferences.KEY_PORT, effectiveInt(McpPreferences.KEY_PORT,
                McpPreferences.DEFAULT_PORT));
        store.setDefault(McpPreferences.KEY_CALL_TIMEOUT_SECONDS,
                effectiveInt(McpPreferences.KEY_CALL_TIMEOUT_SECONDS,
                        McpPreferences.DEFAULT_CALL_TIMEOUT_SECONDS));
        store.setDefault(McpPreferences.KEY_UI_WAIT_SECONDS, effectiveInt(McpPreferences.KEY_UI_WAIT_SECONDS,
                McpPreferences.DEFAULT_UI_WAIT_SECONDS));
        setPreferenceStore(store);
        setTitle("MCP Server");
        setDescription("Configure the embedded MCP server. Apply or OK restarts it with the new settings.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // No workbench initialisation is required.
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(
                McpPreferences.KEY_ENABLED,
                "Enable the MCP server",
                getFieldEditorParent()));

        IntegerFieldEditor port = new IntegerFieldEditor(
                McpPreferences.KEY_PORT,
                "Port:",
                getFieldEditorParent(), 6);
        port.setValidRange(1, 65535);
        addField(port);

        IntegerFieldEditor timeout = new IntegerFieldEditor(
                McpPreferences.KEY_CALL_TIMEOUT_SECONDS,
                "Tool call timeout (seconds):",
                getFieldEditorParent(), 6);
        timeout.setValidRange(McpPreferences.MIN_CALL_TIMEOUT_SECONDS,
                McpPreferences.MAX_CALL_TIMEOUT_SECONDS);
        addField(timeout);

        IntegerFieldEditor uiWait = new IntegerFieldEditor(
                McpPreferences.KEY_UI_WAIT_SECONDS,
                "UI wait budget (seconds):",
                getFieldEditorParent(), 6);
        uiWait.setValidRange(McpPreferences.MIN_UI_WAIT_SECONDS, 120);
        addField(uiWait);

        createTokenRow(getFieldEditorParent());
        createInstructions(getFieldEditorParent());
    }

    /** A read-only field with the bearer token, next to a button that replaces it. */
    private void createTokenRow(Composite parent) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 0;
        row.setLayout(layout);

        new Label(row, SWT.NONE).setText("Bearer token:");

        tokenField = new Text(row, SWT.BORDER | SWT.READ_ONLY);
        tokenField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tokenField.setText(McpServerService.getToken());

        Button regenerate = new Button(row, SWT.PUSH);
        regenerate.setText("Regenerate");
        regenerate.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        regenerate.addListener(SWT.Selection, e -> regenerateToken());
    }

    /** How to point an MCP client at this server, kept in step with the token field. */
    private void createInstructions(Composite parent) {
        instructions = new Text(parent, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.BORDER);
        instructions.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
        instructions.setText(agentInstructions());
        instructions.setBackground(instructions.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
    }

    private void regenerateToken() {
        try {
            // stop/start is inside, so the new token guards the endpoint at once
            McpServerService.getInstance().regenerateToken();
            tokenField.setText(McpServerService.getToken());
            instructions.setText(agentInstructions());
        } catch (McpServerException e) {
            MessageDialog.openError(getShell(), "MCP Server",
                    "Could not regenerate the token: " + e.getMessage());
        }
    }

    private String agentInstructions() {
        String token = McpServerService.getToken();
        return """
                Copy this into the agent configuration (CodeBuddy, Claude Code, ...):

                  Endpoint URL:  %s
                  Header:        Authorization: Bearer %s
                  Token:         %s
                  Transport:     Streamable HTTP

                The token survives IDE restarts. It lives in
                  %s
                and the discovery file
                  %s
                holds the URL and the token together, for tools that read it.

                Regenerate replaces the token and restarts the server, so every configured client
                stops working until its configuration holds the new token shown here."""
                .formatted(currentUrl(), token, token,
                        McpServerService.getTokenFile(), McpServerService.getEndpointFile());
    }

    private String currentUrl() {
        McpEndpoint endpoint = McpServerService.getInstance().getEndpoint();
        if (endpoint != null && endpoint.url() != null && !endpoint.url().isBlank()) {
            return endpoint.url();
        }
        return "http://127.0.0.1:%d/mcp".formatted(McpPreferences.getPort());
    }

    private static boolean effectiveBoolean(String key, boolean fallback) {
        return Platform.getPreferencesService().getBoolean(McpPreferences.QUALIFIER, key, fallback, null);
    }

    private static int effectiveInt(String key, int fallback) {
        return Platform.getPreferencesService().getInt(McpPreferences.QUALIFIER, key, fallback, null);
    }

    @Override
    public boolean performOk() {
        boolean ok = super.performOk();
        if (ok) {
            McpServerLifecycle.reconcile();
        }
        return ok;
    }

    @Override
    public void performApply() {
        super.performApply();
        McpServerLifecycle.reconcile();
    }
}
