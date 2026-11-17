package com.vogella.eclipse.mcp.basic.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.basic.internal.BuildRegistry.SaveOutcome;

/**
 * Saves dirty editors before a build, from a tool call that arrives on a Jetty worker thread.
 * <p>
 * The UI types are imported optionally and touched only here: a headless test JVM has no
 * workbench, and a call there reports that nothing was saved instead of failing the build.
 * A tool thread never calls {@code Display.syncExec}; queueing a runnable and waiting on a
 * latch keeps the UI free to run it and bounds how long a build waits for a busy IDE.
 */
final class DirtyEditorSaver {

	private DirtyEditorSaver() {
	}

	static SaveOutcome saveAll(int timeoutSeconds) {
		long started = System.currentTimeMillis();
		IWorkbench workbench;
		Display display;
		try {
			workbench = PlatformUI.getWorkbench();
			display = workbench.getDisplay();
		} catch (LinkageError | RuntimeException e) {
			return unavailable(started, "No Eclipse workbench is available here, so dirty editors were not saved.");
		}
		if (display == null || display.isDisposed()) {
			return unavailable(started, "The Eclipse display is gone, so dirty editors were not saved.");
		}
		int[] counts = { -1, -1 };
		Throwable[] failure = new Throwable[1];
		CountDownLatch done = new CountDownLatch(1);
		if (Display.getCurrent() == display) {
			save(workbench, counts, failure, done);
		} else {
			display.asyncExec(() -> save(workbench, counts, failure, done));
			try {
				if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
					return unavailable(started, //$NON-NLS-1$
							"The Eclipse UI did not reach the save within %d seconds; it is still queued." //$NON-NLS-1$
									.formatted(Integer.valueOf(timeoutSeconds)));
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return unavailable(started, "Interrupted while waiting for the Eclipse UI to save the editors."); //$NON-NLS-1$
			}
		}
		if (failure[0] != null) {
			return unavailable(started, "Saving the dirty editors failed: " + failure[0]); //$NON-NLS-1$
		}
		return new SaveOutcome(true, true, counts[0], counts[1], System.currentTimeMillis() - started, null);
	}

	private static void save(IWorkbench workbench, int[] counts, Throwable[] failure, CountDownLatch done) {
		try {
			counts[0] = countDirty(workbench);
			// confirm=false so no "save resources" dialog is raised on a client path
			workbench.saveAllEditors(false);
			counts[1] = countDirty(workbench);
		} catch (Throwable t) {
			failure[0] = t;
		} finally {
			done.countDown();
		}
	}

	private static int countDirty(IWorkbench workbench) {
		int dirty = 0;
		for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				for (IEditorPart editor : page.getEditors()) {
					if (editor.isDirty()) {
						dirty++;
					}
				}
			}
		}
		return dirty;
	}

	private static SaveOutcome unavailable(long started, String note) {
		return new SaveOutcome(true, false, -1, -1, System.currentTimeMillis() - started, note);
	}
}
