package com.vogella.eclipse.mcp.stm32.internal;

import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

/** Eagerly installs the DSF event synchronizer before any debug session starts. */
public class Activator extends Plugin {

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		DsfContextSynchronizer.install();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		DsfContextSynchronizer.uninstall();
		super.stop(context);
	}
}
