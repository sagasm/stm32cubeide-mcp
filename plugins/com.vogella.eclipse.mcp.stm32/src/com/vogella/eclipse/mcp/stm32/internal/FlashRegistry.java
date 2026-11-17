package com.vogella.eclipse.mcp.stm32.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps build-and-flash operations so a client can start one and poll its
 * status instead of holding an MCP request open for the whole launch.
 */
public final class FlashRegistry {

	private static final int HISTORY = 20;

	private static final FlashRegistry INSTANCE = new FlashRegistry();

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, Flash> flashes = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Flash> eldest) {
			return size() > HISTORY && !"running".equals(eldest.getValue().state());
		}
	};

	private String lastId;

	public static FlashRegistry getInstance() {
		return INSTANCE;
	}

	private FlashRegistry() {
	}

	public static final class Flash {
		private final String id;
		private final String configuration;
		private final String project;
		private final long startedAt = System.currentTimeMillis();
		private final CountDownLatch finished = new CountDownLatch(1);

		private volatile String state = "running";
		private volatile long endedAt;
		private volatile String sessionId;
		private volatile String note;
		private volatile String error;

		Flash(String id, String configuration, String project) {
			this.id = id;
			this.configuration = configuration;
			this.project = project;
		}

		public String id() {
			return id;
		}

		public String configuration() {
			return configuration;
		}

		public String project() {
			return project;
		}

		public String state() {
			return state;
		}

		public long elapsedMillis() {
			return (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt;
		}

		public String sessionId() {
			return sessionId;
		}

		public String note() {
			return note;
		}

		public String error() {
			return error;
		}

		void done(String sessionId, String note) {
			this.sessionId = sessionId;
			this.note = note;
			this.state = "done";
			this.endedAt = System.currentTimeMillis();
			this.finished.countDown();
		}

		void fail(String error) {
			this.error = error;
			this.state = "failed";
			this.endedAt = System.currentTimeMillis();
			this.finished.countDown();
		}

		boolean await(int seconds) {
			try {
				return finished.await(seconds, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}

	public Flash start(String configuration, String project) {
		String id = "flash-" + ids.incrementAndGet();
		Flash flash = new Flash(id, configuration, project);
		synchronized (flashes) {
			flashes.put(id, flash);
			lastId = id;
		}
		return flash;
	}

	public Flash find(String id) {
		synchronized (flashes) {
			return flashes.get(id);
		}
	}

	public Flash findLatest() {
		synchronized (flashes) {
			return lastId == null ? null : flashes.get(lastId);
		}
	}

	public List<String> ids() {
		synchronized (flashes) {
			return List.copyOf(flashes.keySet());
		}
	}
}
