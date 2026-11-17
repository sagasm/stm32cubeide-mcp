package com.vogella.eclipse.mcp.basic.internal;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Reads the buffer of the text editor open on a file, from a tool call that arrives
 * on a Jetty worker thread.
 * <p>
 * UI types are imported optionally and touched only here: a headless test JVM has
 * no workbench, and a call there simply reports that no editor buffer is available.
 * A tool thread never calls {@code Display.syncExec}; queueing a runnable and waiting
 * on a latch keeps the UI free to run it and bounds how long a read waits for a busy IDE.
 */
final class DirtyEditorReader {

	record Buffer(String content, boolean dirty) {
	}

	private DirtyEditorReader() {
	}

	/** Returns the open editor's buffer, or {@code null} when no workbench or text editor is available. */
	static Buffer read(IFile file, int timeoutSeconds) {
		IWorkbench workbench;
		Display display;
		try {
			workbench = PlatformUI.getWorkbench();
			display = workbench.getDisplay();
		} catch (LinkageError | RuntimeException e) {
			return null;
		}
		if (display == null || display.isDisposed()) {
			return null;
		}
		Buffer[] result = new Buffer[1];
		Throwable[] failure = new Throwable[1];
		CountDownLatch done = new CountDownLatch(1);
		if (Display.getCurrent() == display) {
			readFromUi(workbench, file, result, failure, done);
		} else {
			display.asyncExec(() -> readFromUi(workbench, file, result, failure, done));
			try {
				if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
					return null;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		return failure[0] == null ? result[0] : null;
	}

	private static void readFromUi(IWorkbench workbench, IFile file, Buffer[] result, Throwable[] failure,
			CountDownLatch done) {
		try {
			for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
				for (IWorkbenchPage page : window.getPages()) {
					for (IEditorPart editor : page.getEditors()) {
						IEditorInput input = editor.getEditorInput();
						IFile edited = input == null ? null : input.getAdapter(IFile.class);
						if (edited == null || !edited.getFullPath().equals(file.getFullPath())) {
							continue;
						}
						IDocument document = documentOf(editor, input);
						if (document != null) {
							result[0] = new Buffer(document.get(), editor.isDirty());
							return;
						}
					}
				}
			}
		} catch (Throwable t) {
			failure[0] = t;
		} finally {
			done.countDown();
		}
	}

	/**
	 * The Tycho target contributes the org.eclipse.ui facade but not the
	 * workbench.texteditor bundle, so a text editor's document provider is reached
	 * reflectively instead of through an import the target cannot resolve.
	 */
	private static IDocument documentOf(IEditorPart editor, IEditorInput input) {
		try {
			Object provider = editor.getClass().getMethod("getDocumentProvider").invoke(editor); //$NON-NLS-1$
			if (provider == null) {
				return null;
			}
			for (Method method : provider.getClass().getMethods()) {
				if (!"getDocument".equals(method.getName()) || method.getParameterCount() != 1 //$NON-NLS-1$
						|| !method.getParameterTypes()[0].isAssignableFrom(input.getClass())) {
					continue;
				}
				Object document = method.invoke(provider, input);
				return document instanceof IDocument text ? text : null;
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// the editor is not a text editor with a document provider
		}
		return null;
	}
}
