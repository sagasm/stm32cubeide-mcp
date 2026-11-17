package com.vogella.eclipse.mcp.basic.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Snapshots of the workspace's project open/closed states, so that a later
 * eclipse_list_projects call can answer "what changed since that state change"
 * without the client keeping its own copy of every project's state.
 */
final class ProjectStateBaselines {

	static final String MARKER_PREFIX = "mcpprojstate"; //$NON-NLS-1$

	private static final int KEEP = 10;

	private static final AtomicLong IDS = new AtomicLong();

	private static final Map<String, Map<String, String>> BASELINES = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
			return size() > KEEP;
		}
	};

	private ProjectStateBaselines() {
	}

	static synchronized String take(Map<String, String> states) {
		String id = "%s-%d".formatted(MARKER_PREFIX, Long.valueOf(IDS.incrementAndGet())); //$NON-NLS-1$
		BASELINES.put(id, new LinkedHashMap<>(states));
		return id;
	}

	/** The snapshot, or {@code null} when the marker is unknown or has aged out. */
	static synchronized Map<String, String> of(String marker) {
		return BASELINES.get(marker);
	}

	static synchronized List<String> ids() {
		return List.copyOf(BASELINES.keySet());
	}
}
