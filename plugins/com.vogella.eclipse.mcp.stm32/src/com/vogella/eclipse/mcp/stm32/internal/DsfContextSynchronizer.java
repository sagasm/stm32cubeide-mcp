package com.vogella.eclipse.mcp.stm32.internal;

import org.eclipse.cdt.dsf.debug.service.IRunControl.IResumedDMEvent;
import org.eclipse.cdt.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.cdt.dsf.service.DsfServiceEventHandler;
import org.eclipse.cdt.dsf.service.DsfSession;

/**
 * Keeps the debug steering caches warm from DSF's own state-change events.
 *
 * <p>The MCP debug tools used to learn the thread-group id only while they
 * themselves read a suspended session. A session started from the Debug view,
 * or one that was resumed without an MCP read in between, therefore had no
 * cached context and {@code pause} failed with "no thread-group to steer".
 * DSF emits {@link ISuspendedDMEvent}/{@link IResumedDMEvent} for every
 * session regardless of who started it, and each event carries the live
 * execution context. Listening to that stream makes the caches reflect the
 * target for all sessions, which is the same source the Debug view uses.</p>
 */
public final class DsfContextSynchronizer {

	private static final DsfSession.SessionStartedListener STARTED = DsfContextSynchronizer::onSessionStarted;
	private static final DsfSession.SessionEndedListener ENDED = DsfContextSynchronizer::onSessionEnded;
	private static volatile boolean installed;

	private DsfContextSynchronizer() {
	}

	/** Registers the global listeners once, at bundle activation. */
	public static synchronized void install() {
		if (installed) {
			return;
		}
		DsfSession.addSessionStartedListener(STARTED);
		DsfSession.addSessionEndedListener(ENDED);
		installed = true;
	}

	/** Unregisters the global listeners at bundle shutdown. */
	public static synchronized void uninstall() {
		if (!installed) {
			return;
		}
		DsfSession.removeSessionStartedListener(STARTED);
		DsfSession.removeSessionEndedListener(ENDED);
		installed = false;
	}

	private static void onSessionStarted(DsfSession session) {
		// addServiceEventListener asserts it is called on the DSF executor thread.
		// sessionStarted is delivered off-thread, so hop onto that thread.
		session.getExecutor().execute(() -> {
			try {
				session.addServiceEventListener(new SessionContextListener(session.getId()), null);
			} catch (RuntimeException e) {
				// Never break a launch because of an optional listener; the
				// on-demand caching path in DebugSessions still covers MCP launches.
			}
		});
	}

	private static void onSessionEnded(DsfSession session) {
		// The session and its listener become unreachable together; no explicit
		// removal is needed because DsfSession drops its listener table on dispose.
	}

	/** Receives DSF state events and feeds the live context into the caches. */
	public static final class SessionContextListener {
		private final String sessionId;

		SessionContextListener(String sessionId) {
			this.sessionId = sessionId;
		}

		@DsfServiceEventHandler
		public void suspended(ISuspendedDMEvent event) {
			DebugSessions.noteContexts(sessionId, event.getDMContext());
		}

		@DsfServiceEventHandler
		public void resumed(IResumedDMEvent event) {
			DebugSessions.noteContexts(sessionId, event.getDMContext());
		}
	}
}
