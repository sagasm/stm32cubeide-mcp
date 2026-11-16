package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolRegistry;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.server.McpServerService;

/**
 * Hot-reloads MCP plugin bundles into the running IDE so a code change does
 * not require an IDE restart. Updates each jar into its installed bundle,
 * refreshes the framework, clears the tool cache and restarts the MCP server.
 * The answer is returned first and the disruptive work runs in a delayed job,
 * because refreshing/restarting touches the very server that owes the reply.
 */
public final class HotReloadTool implements IMcpTool {

	private static final String MCP_PREFIX = "com.vogella.eclipse.mcp."; //$NON-NLS-1$
	private static final String SELF_SERVER = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$
	private static final String SELF_CORE = "com.vogella.eclipse.mcp.core"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "mcp_hot_reload"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Hot-reload MCP plugin bundle jars into the running IDE WITHOUT restarting it. Give 'jar' as an absolute path to one built bundle jar (or a single path to a directory: every com.vogella.eclipse.mcp.*.jar inside is reloaded). This updates each installed bundle from the jar, refreshes the OSGi framework, clears the cached tool list and restarts the MCP server so new/changed tools appear in tools/list immediately. dryRun (default true) reports the bundles and versions that would change. The disruptive work happens a couple of seconds AFTER this answer: the connection will drop and the client must reconnect before using tools. Only com.vogella.eclipse.mcp.* bundles are accepted; IDE platform bundles are never touched. A hot reload is not persisted by p2: the original jars return at the next IDE restart, so run the normal install step for a permanent change."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["jar"],
				  "properties": {
				    "jar":       {"type":"string","description":"Absolute path to a built MCP bundle jar, or to a directory containing several com.vogella.eclipse.mcp.*.jar files."},
				    "dryRun":    {"type":"boolean","default":true,"description":"Report what would be reloaded and change nothing."},
				    "delayMs":   {"type":"integer","default":1800,"minimum":200,"maximum":30000,"description":"Milliseconds to wait after this answer before refreshing and restarting the server; give the client time to receive the response."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String pathArg = args.getString("jar"); //$NON-NLS-1$
		if (pathArg == null) {
			return McpToolResult.error("The 'jar' argument is required: an absolute jar path or a directory of jars."); //$NON-NLS-1$
		}
		Path path = Path.of(pathArg);
		if (!path.isAbsolute()) {
			return McpToolResult.error("'%s' is not an absolute path.".formatted(pathArg)); //$NON-NLS-1$
		}
		List<Path> jars = resolveJars(path);
		if (jars.isEmpty()) {
			return McpToolResult.error("No com.vogella.eclipse.mcp.*.jar found at '%s'.".formatted(pathArg)); //$NON-NLS-1$
		}

		BundleContext context = frameworkContext();
		if (context == null) {
			return McpToolResult.error("No OSGi framework is running."); //$NON-NLS-1$
		}

		List<JarPlan> plan = new ArrayList<>();
		for (Path jar : jars) {
			String symbolicName;
			try {
				symbolicName = symbolicNameOf(jar);
			} catch (IOException e) {
				return McpToolResult.error("Could not read '%s': %s".formatted(jar, e.getMessage())); //$NON-NLS-1$
			}
			if (symbolicName == null || !symbolicName.startsWith(MCP_PREFIX)) {
				return McpToolResult.error("Refusing '%s': only %s* bundles may be hot-reloaded, got '%s'." //$NON-NLS-1$
						.formatted(jar, MCP_PREFIX, symbolicName)); //$NON-NLS-1$
			}
			Bundle installed = installedBundle(context, symbolicName);
			plan.add(new JarPlan(jar, symbolicName, installed));
		}

		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		JsonObject result = new JsonObject();
		result.put("dryRun", Boolean.valueOf(dryRun));
		result.put("requested", Integer.valueOf(plan.size()));
		JsonArray items = new JsonArray();
		for (JarPlan p : plan) {
			JsonObject one = new JsonObject();
			one.put("jar", p.jar.toString());
			one.put("symbolicName", p.symbolicName);
			one.put("installedVersion", p.installed == null ? null : p.installed.getVersion().toString());
			one.put("action", p.installed == null ? "install" : "update"); //$NON-NLS-1$ //$NON-NLS-2$
			items.add(one);
		}
		result.put("bundles", items);
		if (dryRun) {
			result.put("note", "Dry run: nothing changed. Pass dryRun=false to apply. The connection drops briefly while the server restarts; reconnect before the next call."); //$NON-NLS-1$
			return McpToolResult.of(result.toString());
		}

		// update the bundles now, while we can still answer the caller
		List<Bundle> touched = new ArrayList<>();
		for (JarPlan p : plan) {
			try (InputStream in = Files.newInputStream(p.jar)) {
				if (p.installed == null) {
					p.installed = context.installBundle(p.jar.toUri().toString(), in);
					p.installed.start(0);
				} else {
					p.installed.update(in);
				}
				touched.add(p.installed);
			} catch (IOException | BundleException e) {
				return McpToolResult.error("Updating %s failed: %s".formatted(p.symbolicName, e.getMessage())); //$NON-NLS-1$
			}
		}

		int delayMs = args.getInt("delayMs", 1800, 200, 30000); //$NON-NLS-1$
		scheduleRestart(context, touched, delayMs);
		result.put("status", "scheduled"); //$NON-NLS-1$
		result.put("delayMs", Integer.valueOf(delayMs));
		result.put("note", "Bundles updated. The OSGi refresh and MCP server restart follow in %d ms; the connection will drop, reconnect before the next call.".formatted(Integer.valueOf(delayMs))); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	/** Refreshes, clears the tool cache and restarts the server, after answering. */
	private static void scheduleRestart(BundleContext context, List<Bundle> touched, int delayMs) {
		Job job = new Job("Hot reload MCP bundles") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					FrameworkWiring wiring = context.getBundle(0).adapt(FrameworkWiring.class);
					Set<Bundle> closure = new HashSet<>(touched);
					if (wiring != null) {
						closure.addAll(wiring.getDependencyClosure(new HashSet<>(touched)));
					}
					// Never refresh core or this server bundle: a refresh replaces
					// their classes and their static singletons, so the reset() and
					// stop()/start() below would act on stale instances and the new
					// tool list would never be published. Refreshing only the tool
					// bundles keeps both singletons stable while their classes and
					// plugin.xml contributions are reloaded.
					int excluded = 0;
					for (java.util.Iterator<Bundle> it = closure.iterator(); it.hasNext();) {
						Bundle b = it.next();
						String name = b.getSymbolicName();
						if (SELF_SERVER.equals(name) || SELF_CORE.equals(name)) {
							it.remove();
							excluded++;
						}
					}
					CountDownLatch done = new CountDownLatch(1);
					if (wiring != null && !closure.isEmpty()) {
						wiring.refreshBundles(closure, event -> done.countDown());
					} else {
						done.countDown();
					}
					done.await(20, TimeUnit.SECONDS);
					McpToolRegistry.getInstance().reset();
					McpServerService service = McpServerService.getInstance();
					service.stop();
					service.start();
					org.eclipse.core.runtime.ILog.get().info(
							"MCP hot reload finished: %d bundle(s) refreshed (%d server/core excluded), tools and server rebuilt".formatted( //$NON-NLS-1$
									Integer.valueOf(closure.size()), Integer.valueOf(excluded)));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (Exception e) {
					org.eclipse.core.runtime.ILog.get().error("MCP hot reload failed", e); //$NON-NLS-1$
				}
				return Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.schedule(delayMs);
	}

	private static List<Path> resolveJars(Path path) {
		if (Files.isRegularFile(path)) {
			return List.of(path);
		}
		if (!Files.isDirectory(path)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(path)) {
			return stream.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".jar")) //$NON-NLS-1$
					.filter(p -> p.getFileName().toString().startsWith(MCP_PREFIX))
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static String symbolicNameOf(Path jar) throws IOException {
		try (JarFile opened = new JarFile(jar.toFile())) {
			Manifest manifest = opened.getManifest();
			if (manifest == null) {
				return null;
			}
			Attributes main = manifest.getMainAttributes();
			String header = main.getValue(Constants.BUNDLE_SYMBOLICNAME);
			if (header == null) {
				return null;
			}
			int semi = header.indexOf(';');
			return (semi < 0 ? header : header.substring(0, semi)).trim();
		}
	}

	private static Bundle installedBundle(BundleContext context, String symbolicName) {
		for (Bundle bundle : context.getBundles()) {
			if (symbolicName.equals(bundle.getSymbolicName())) {
				return bundle;
			}
		}
		return null;
	}

	private static BundleContext frameworkContext() {
		Bundle bundle = FrameworkUtil.getBundle(HotReloadTool.class);
		return bundle == null ? null : bundle.getBundleContext();
	}

	private static final class JarPlan {
		final Path jar;
		final String symbolicName;
		Bundle installed;

		JarPlan(Path jar, String symbolicName, Bundle installed) {
			this.jar = jar;
			this.symbolicName = symbolicName;
			this.installed = installed;
		}
	}
}
