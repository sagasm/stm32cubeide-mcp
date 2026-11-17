package com.vogella.eclipse.mcp.stm32.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.ImmediateDataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.ImmediateRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.Query;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.IBreakpoints;
import org.eclipse.cdt.dsf.debug.service.IProcesses;
import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.debug.service.IStack;
import org.eclipse.cdt.dsf.debug.service.command.ICommand;
import org.eclipse.cdt.dsf.debug.service.command.ICommandControlService;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.cdt.dsf.mi.service.IMICommandControl;
import org.eclipse.cdt.dsf.mi.service.IMIProcesses;
import org.eclipse.cdt.dsf.mi.service.command.CommandFactory;
import org.eclipse.cdt.dsf.mi.service.command.output.MIArg;
import org.eclipse.cdt.dsf.mi.service.command.output.MIBreakInsertInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIBreakListInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIBreakpoint;
import org.eclipse.cdt.dsf.mi.service.command.output.MIDataEvaluateExpressionInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIDataReadMemoryBytesInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIFrame;
import org.eclipse.cdt.dsf.mi.service.command.output.MIInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIListThreadGroupsInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIStackListArgumentsInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIStackListLocalsInfo;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.MemoryByte;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The debug sessions this bundle has started, keyed by a session id that is
 * stable for the lifetime of the launch. Read and control tools both go here.
 *
 * DSF (CDT GDB) launches never expose a classic {@link IDebugTarget}: the
 * Debug view renders them through the DSF view model, not the classic debug
 * model. State and steering therefore go through the DSF service layer
 * ({@link IProcesses}, {@link IRunControl}, {@link IStack}) on the DSF
 * executor, which is what the Debug view's content provider does.
 */
public final class DebugSessions {

	private static final Map<String, ILaunch> SESSIONS = new ConcurrentHashMap<>();

	private DebugSessions() {
	}

	/** Registers a launch and returns its session id. */
	public static String register(ILaunch launch) {
		String id = "dbg-" + Integer.toHexString(System.identityHashCode(launch));
		SESSIONS.put(id, launch);
		return id;
	}

	/** The launch of a session id, or null when the id is unknown. */
	public static ILaunch get(String sessionId) {
		return SESSIONS.get(sessionId);
	}

	/**
	 * True only when the DSF services used by the debug tools are all available.
	 * A GdbLaunch's session becomes active before these services exist, so this
	 * is the reliable readiness check when picking a freshly started launch.
	 */
	public static boolean isDsfReady(GdbLaunch launch) {
		DsfSession session = launch.getSession();
		if (session == null || !session.isActive()) {
			return false;
		}
		DsfServicesTracker tracker = servicesTracker(session);
		try {
			return tracker.getService(IProcesses.class) != null
					&& tracker.getService(IRunControl.class) != null
					&& tracker.getService(IStack.class) != null
					&& tracker.getService(ICommandControlService.class) != null;
		} finally {
			tracker.dispose();
		}
	}

	/** Describes every known session, oldest first, adopting sessions started outside this bundle. */
	public static JsonArray describeAll() {
		// A session the user started from the IDE Debug button is not in the
		// registry; adopt it so the control tool can also steer it.
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		for (ILaunch launch : manager.getLaunches()) {
			if (!SESSIONS.containsValue(launch)) {
				register(launch);
			}
		}
		JsonArray array = new JsonArray();
		for (Map.Entry<String, ILaunch> entry : SESSIONS.entrySet()) {
			array.add(describe(entry.getValue(), entry.getKey()));
		}
		return array;
	}

	/**
	 * The state of a debug session: launching, running, suspended or
	 * terminated, and, once suspended, the top stack frame with its source
	 * line, which is where a breakpoint landed.
	 */
	public static JsonObject describe(ILaunch launch, String sessionId) {
		JsonObject o = new JsonObject();
		o.put("sessionId", sessionId);
		ILaunchConfiguration configuration = launch.getLaunchConfiguration();
		o.put("configuration", configuration == null ? "" : configuration.getName());
		if (launch instanceof GdbLaunch gdbLaunch) {
			return describeDsf(gdbLaunch, o);
		}
		return describeClassic(launch, o);
	}

	private static JsonObject describeClassic(ILaunch launch, JsonObject o) {
		IDebugTarget target = launch.getDebugTarget();
		if (target == null) {
			o.put("state", "launching");
			return o;
		}
		String state = "running";
		if (target.isTerminated()) {
			state = "terminated";
		} else if (target.isSuspended()) {
			state = "suspended";
		}
		o.put("state", state);
		o.put("targetName", safeText(target::getName));
		o.put("canTerminate", target.canTerminate());
		try {
			if (target.isTerminated() || !target.hasThreads()) {
				return o;
			}
			JsonArray threads = new JsonArray();
			for (IThread thread : target.getThreads()) {
				JsonObject t = new JsonObject();
				t.put("name", safeText(thread::getName));
				t.put("suspended", thread.isSuspended());
				if (thread.isSuspended() && thread.hasStackFrames()) {
					IStackFrame top = thread.getTopStackFrame();
					t.put("topFrame", safeText(top::getName));
					t.put("line", top.getLineNumber());
					if (thread.getBreakpoints().length > 0) {
						t.put("hitBreakpoint", true);
					}
				}
				threads.add(t);
			}
			o.put("threads", threads);
		} catch (CoreException e) {
			o.put("stateError", String.valueOf(e.getMessage()));
		}
		return o;
	}

	private static JsonObject describeDsf(GdbLaunch launch, JsonObject o) {
		DsfSession session = launch.getSession();
		if (session == null || !session.isActive()) {
			o.put("state", launch.isTerminated() ? "terminated" : "launching");
			o.put("canTerminate", launch.canTerminate());
			return o;
		}
		final AtomicBoolean executed = new AtomicBoolean();
		Query<JsonObject> query = new Query<>() {
			@Override
			protected void execute(DataRequestMonitor<JsonObject> rm) {
				executed.set(true);
				DsfServicesTracker tracker = servicesTracker(session);
				IProcesses processes = tracker.getService(IProcesses.class);
				IRunControl runControl = tracker.getService(IRunControl.class);
				IStack stack = tracker.getService(IStack.class);
				ICommandControlService commandControl = tracker.getService(ICommandControlService.class);
				IMICommandControl miCommandControl = tracker.getService(IMICommandControl.class);
				CommandFactory commandFactory = miCommandControl != null ? miCommandControl.getCommandFactory()
						: new CommandFactory();
				IMIProcesses miProcesses = tracker.getService(IMIProcesses.class);
				tracker.dispose();
				if (processes == null || runControl == null || stack == null || commandControl == null) {
					o.put("stateError", "DSF services not available yet.");
					o.put("state", launch.isTerminated() ? "terminated" : "launching");
					rm.done(o);
					return;
				}
				// The DSF services read cached data; the caches are normally
				// refreshed by the Debug view's view model, which a bare
				// query does not trigger. Flush and refresh first, otherwise
				// getProcessesBeingDebugged answers with an empty cache.
				commandControl.flushAllCachesAndRefresh(new ImmediateRequestMonitor() {
					@Override
					protected void handleCompleted() {
						collectDsfState(launch, processes, runControl, stack, commandControl,
						commandFactory != null ? commandFactory : new CommandFactory(), miProcesses, o, rm);
					}
				});
			}
		};
		JsonArray processLabels = new JsonArray();
		for (org.eclipse.debug.core.model.IProcess process : launch.getProcesses()) {
			processLabels.add(safeText(process::getLabel));
		}
		o.put("gdbProcesses", processLabels);
		launch.getDsfExecutor().execute(query);
		try {
			return query.get(8, TimeUnit.SECONDS);
		} catch (Exception e) {
			Throwable cause = e;
			while (cause.getCause() != null && cause.getCause() != cause) {
				cause = cause.getCause();
			}
			String detail;
			if (cause instanceof java.util.concurrent.TimeoutException) {
				detail = "DSF query timed out after 8s, executed=" + executed.get()
						+ ", sessionActive=" + session.isActive();
			} else {
				java.io.StringWriter stack = new java.io.StringWriter();
				cause.printStackTrace(new java.io.PrintWriter(stack));
				detail = "DSF query failed: " + cause.getClass().getName() + ": " + cause.getMessage()
						+ "\n" + stack.toString();
			}
			o.put("stateError", detail);
			o.put("state", launch.isTerminated() ? "terminated" : "launching");
			return o;
		}
	}

	private static DsfServicesTracker servicesTracker(DsfSession session) {
		return new DsfServicesTracker(FrameworkUtil.getBundle(DsfSession.class).getBundleContext(), session.getId());
	}

	/** Runs on the DSF executor: asks the DSF services for target state and the top frame. */
	private static void collectDsfState(GdbLaunch launch, IProcesses processes, IRunControl runControl, IStack stack,
			ICommandControlService commandControl, CommandFactory commandFactory, IMIProcesses miProcesses,
			JsonObject o, DataRequestMonitor<JsonObject> rm) {
		o.put("canTerminate", launch.canTerminate());
		o.put("rootContext", String.valueOf(commandControl.getContext()));
		try {
			// fConnected probe: GDBProcesses gates getProcessesBeingDebugged on
			// an IStartedDMEvent for the container; canDetach returns that flag.
			processes.canDetachDebuggerFromProcess(commandControl.getContext(),
					new ImmediateDataRequestMonitor<Boolean>() {
						@Override
						protected void handleCompleted() {
							o.put("dsfConnected", Boolean.TRUE.equals(getData()));
							// Raw MI probe: bypass every cache and ask gdb directly.
							commandControl.queueCommand(
									commandFactory.createMIListThreadGroups(commandControl.getContext()),
									new ImmediateDataRequestMonitor<MIListThreadGroupsInfo>() {
										@Override
										protected void handleCompleted() {
											MIListThreadGroupsInfo info = getData();
											if (info != null && info.getGroupList() != null) {
												JsonArray groups = new JsonArray();
												for (MIListThreadGroupsInfo.IThreadGroupInfo g : info.getGroupList()) {
													JsonObject go = new JsonObject();
													go.put("pid", safeText(g::getPid));
													go.put("name", safeText(g::getName));
													go.put("groupId", safeText(g::getGroupId));
													groups.add(go);
												}
												o.put("miGroups", groups);
											} else {
												o.put("miGroups", "no response: " + getStatus());
											}
											String groupId = null;
											if (info != null && info.getGroupList() != null && info.getGroupList().length > 0) {
												groupId = info.getGroupList()[0].getGroupId();
											}
											queryExecutionDsfContexts(processes, runControl, commandControl, groupId,
													miProcesses,
					new ImmediateDataRequestMonitor<IRunControl.IExecutionDMContext[]>() {
				@Override
				protected void handleSuccess() {
					IRunControl.IExecutionDMContext[] contexts = getData();
					if (contexts.length == 0) {
						// Connected but nothing reported as being debugged yet:
						// the inferior is still being loaded or started.
						o.put("state", launch.isTerminated() ? "terminated" : "launching");
						rm.done(o);
						return;
					}
					JsonArray threads = new JsonArray();
					AtomicInteger pending = new AtomicInteger(contexts.length);
					AtomicBoolean anySuspended = new AtomicBoolean();
					Runnable finish = () -> {
						if (pending.decrementAndGet() == 0) {
							o.put("state", anySuspended.get() ? "suspended" : "running");
							o.put("threads", threads);
							rm.done(o);
						}
					};
					for (IRunControl.IExecutionDMContext context : contexts) {
						JsonObject t = new JsonObject();
						t.put("name", String.valueOf(context));
						boolean suspended = runControl.isSuspended(context);
						t.put("suspended", suspended);
						if (suspended) {
							anySuspended.set(true);
							stack.getTopFrame(context,
									new ImmediateDataRequestMonitor<IStack.IFrameDMContext>() {
										@Override
										protected void handleSuccess() {
											stack.getFrameData(getData(),
													new ImmediateDataRequestMonitor<IStack.IFrameDMData>() {
														@Override
														protected void handleSuccess() {
															IStack.IFrameDMData data = getData();
															t.put("topFrame", safeText(data::getFunction));
															t.put("line", data.getLine());
															t.put("file", safeText(data::getFile));
															threads.add(t);
															finish.run();
														}

														@Override
														protected void handleFailure() {
															threads.add(t);
															finish.run();
														}
													});
										}

										@Override
										protected void handleFailure() {
											threads.add(t);
											finish.run();
										}
									});
						} else {
							threads.add(t);
							finish.run();
						}
					}
				}

				@Override
				protected void handleFailure() {
					o.put("stateError", String.valueOf(getStatus().getMessage()));
					o.put("state", launch.isTerminated() ? "terminated" : "launching");
					rm.done(o);
				}
					});
							}
						});
						}
					});
		} catch (Exception e) {
			o.put("stateError", String.valueOf(e.getMessage()));
			o.put("state", launch.isTerminated() ? "terminated" : "launching");
			rm.done(o);
		}
	}

	/**
	 * Resolves every execution context the session is currently debugging.
	 * Runs on the DSF executor. The chain is: command control root context ->
	 * processes being debugged -> debugging context of each process; a
	 * container context is expanded into its threads.
	 */
	private static void queryExecutionDsfContexts(IProcesses processes, IRunControl runControl,
			ICommandControlService commandControl, String groupId, IMIProcesses miProcesses,
			DataRequestMonitor<IRunControl.IExecutionDMContext[]> rm) {
		if (groupId != null && miProcesses != null) {
			// Build the container context ourselves from the raw MI answer:
			// the service's fContainerCommandCache may still hold a stale
			// empty list from the startup phase, while this branch uses a
			// different (thread) cache and yields the execution contexts.
			IProcesses.IProcessDMContext procDmc = miProcesses
					.createProcessContext(commandControl.getContext(), groupId);
			IRunControl.IContainerDMContext containerDmc = miProcesses.createContainerContext(procDmc, groupId);
			processes.getProcessesBeingDebugged(containerDmc,
					new ImmediateDataRequestMonitor<IDMContext[]>() {
						@Override
						protected void handleSuccess() {
							List<IRunControl.IExecutionDMContext> collected = new ArrayList<>();
							for (IDMContext ctx : getData()) {
								if (ctx instanceof IRunControl.IExecutionDMContext exec) {
									collected.add(exec);
								}
							}
							rm.done(collected.toArray(new IRunControl.IExecutionDMContext[0]));
						}

						@Override
						protected void handleFailure() {
							rm.setStatus(getStatus());
							rm.done();
						}
					});
			return;
		}
		processes.getProcessesBeingDebugged(commandControl.getContext(),
				new ImmediateDataRequestMonitor<IDMContext[]>() {
					@Override
					protected void handleSuccess() {
						IDMContext[] processContexts = getData();
						if (processContexts == null || processContexts.length == 0) {
							rm.done(new IRunControl.IExecutionDMContext[0]);
							return;
						}
						List<IRunControl.IExecutionDMContext> collected = new ArrayList<>();
						AtomicInteger pending = new AtomicInteger(processContexts.length);
						Runnable finish = () -> {
							if (pending.decrementAndGet() == 0) {
								rm.done(collected.toArray(new IRunControl.IExecutionDMContext[0]));
							}
						};
						for (IDMContext processContext : processContexts) {
							if (!(processContext instanceof IProcesses.IThreadDMContext threadContext)) {
								finish.run();
								continue;
							}
							processes.getDebuggingContext(threadContext,
									new ImmediateDataRequestMonitor<IDMContext>() {
										@Override
										protected void handleSuccess() {
											IDMContext debuggingContext = getData();
											if (debuggingContext instanceof IRunControl.IContainerDMContext container) {
												runControl.getExecutionContexts(container,
														new ImmediateDataRequestMonitor<IRunControl.IExecutionDMContext[]>() {
															@Override
															protected void handleSuccess() {
																synchronized (collected) {
																	for (IRunControl.IExecutionDMContext context : getData()) {
																		collected.add(context);
																	}
																}
																finish.run();
															}

															@Override
															protected void handleFailure() {
																finish.run();
															}
														});
											} else if (debuggingContext instanceof IRunControl.IExecutionDMContext exec) {
												synchronized (collected) {
													collected.add(exec);
												}
												finish.run();
											} else {
												finish.run();
											}
										}

										@Override
										protected void handleFailure() {
											finish.run();
										}
									});
						}
					}

					@Override
					protected void handleFailure() {
						rm.setStatus(getStatus());
						rm.done();
					}
				});
	}

	/**
	 * Steers a DSF launch: continue, pause, step_over, step_into. Runs the
	 * DSF service call on the DSF executor and blocks briefly for it.
	 */
	public static void steerDsf(GdbLaunch launch, String action, int threadIndex) throws CoreException {
		DsfSession session = launch.getSession();
		if (session == null || !session.isActive()) {
			throw new CoreException(Status.error("The DSF session is not active."));
		}
		Query<Void> query = new Query<>() {
			@Override
			protected void execute(DataRequestMonitor<Void> rm) {
				DsfServicesTracker tracker = servicesTracker(session);
				IProcesses processes = tracker.getService(IProcesses.class);
				IRunControl runControl = tracker.getService(IRunControl.class);
				ICommandControlService commandControl = tracker.getService(ICommandControlService.class);
				IMICommandControl miCommandControl = tracker.getService(IMICommandControl.class);
				CommandFactory commandFactory = miCommandControl != null ? miCommandControl.getCommandFactory()
						: new CommandFactory();
				IMIProcesses miProcesses = tracker.getService(IMIProcesses.class);
				tracker.dispose();
				if (processes == null || runControl == null || commandControl == null || miProcesses == null) {
					rm.setStatus(Status.error("DSF services not available yet."));
					rm.done();
					return;
				}
				// Raw MI first: the service's container list may be a stale
				// empty cache entry; the thread cache path is reliable.
				commandControl.queueCommand(commandFactory.createMIListThreadGroups(commandControl.getContext()),
						new ImmediateDataRequestMonitor<MIListThreadGroupsInfo>() {
							@Override
							protected void handleCompleted() {
								MIListThreadGroupsInfo info = getData();
								String groupId = null;
								if (info != null && info.getGroupList() != null && info.getGroupList().length > 0) {
									groupId = info.getGroupList()[0].getGroupId();
								}
								if (groupId == null) {
									rm.setStatus(Status.error("gdb reports no thread-group to steer."));
									rm.done();
									return;
								}
								IProcesses.IProcessDMContext procDmc = miProcesses
										.createProcessContext(commandControl.getContext(), groupId);
								IRunControl.IContainerDMContext containerDmc = miProcesses
										.createContainerContext(procDmc, groupId);
								processes.getProcessesBeingDebugged(containerDmc,
										new ImmediateDataRequestMonitor<IDMContext[]>() {
											@Override
											protected void handleSuccess() {
												List<IRunControl.IExecutionDMContext> execs = new ArrayList<>();
												for (IDMContext ctx : getData()) {
													if (ctx instanceof IRunControl.IExecutionDMContext exec) {
														execs.add(exec);
													}
												}
												if (execs.isEmpty()) {
													rm.setStatus(Status.error("No execution context to steer."));
													rm.done();
													return;
												}
												steer(runControl, action, threadIndex, execs, rm);
											}

											@Override
											protected void handleFailure() {
												rm.setStatus(getStatus());
												rm.done();
											}
										});
							}
						});
			}
		};
		launch.getDsfExecutor().execute(query);
		try {
			query.get(3, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new CoreException(Status.error("Steering the DSF target failed: " + e.getMessage()));
		}
	}

	private static void steer(IRunControl runControl, String action, int threadIndex,
			List<IRunControl.IExecutionDMContext> contexts, DataRequestMonitor<Void> rm) {
		IRunControl.IExecutionDMContext context = contexts.get(Math.min(threadIndex, contexts.size() - 1));
		switch (action) {
		case "continue" -> runControl.resume(context, rm);
		case "pause" -> runControl.suspend(context, rm);
		case "step_over" -> runControl.step(context, IRunControl.StepType.STEP_OVER, rm);
		case "step_into" -> runControl.step(context, IRunControl.StepType.STEP_INTO, rm);
		default -> {
			rm.setStatus(Status.error("The action '" + action + "' steers no DSF target."));
			rm.done();
		}
		}
	}

	/**
	 * Evaluates a C/ST expression in the top frame of a suspended DSF thread via
	 * MI -data-evaluate-expression. Runs on the DSF executor and blocks briefly.
	 */
	public static JsonObject evaluateDsf(GdbLaunch launch, String expression, int threadIndex) throws CoreException {
		if (expression == null || expression.isBlank()) {
			throw new CoreException(Status.error("The 'expression' argument must not be empty."));
		}
		DsfSession session = launch.getSession();
		if (session == null || !session.isActive()) {
			throw new CoreException(Status.error("The DSF session is not active."));
		}
		Query<JsonObject> query = new Query<>() {
			@Override
			protected void execute(DataRequestMonitor<JsonObject> rm) {
				DsfServicesTracker tracker = servicesTracker(session);
				IProcesses processes = tracker.getService(IProcesses.class);
				IRunControl runControl = tracker.getService(IRunControl.class);
				IStack stack = tracker.getService(IStack.class);
				ICommandControlService commandControl = tracker.getService(ICommandControlService.class);
				IMICommandControl miCommandControl = tracker.getService(IMICommandControl.class);
				CommandFactory commandFactory = miCommandControl != null ? miCommandControl.getCommandFactory()
						: new CommandFactory();
				IMIProcesses miProcesses = tracker.getService(IMIProcesses.class);
				tracker.dispose();
				if (processes == null || runControl == null || stack == null || commandControl == null
						|| miProcesses == null) {
					rm.setStatus(Status.error("DSF services not available yet."));
					rm.done();
					return;
				}
				commandControl.queueCommand(commandFactory.createMIListThreadGroups(commandControl.getContext()),
						new ImmediateDataRequestMonitor<MIListThreadGroupsInfo>() {
							@Override
							protected void handleCompleted() {
								MIListThreadGroupsInfo info = getData();
								String groupId = info != null && info.getGroupList() != null
										&& info.getGroupList().length > 0
												? info.getGroupList()[0].getGroupId()
												: null;
								if (groupId == null) {
									rm.setStatus(Status.error("gdb reports no thread-group to evaluate in."));
									rm.done();
									return;
								}
								IProcesses.IProcessDMContext procDmc = miProcesses
										.createProcessContext(commandControl.getContext(), groupId);
								IRunControl.IContainerDMContext containerDmc = miProcesses
										.createContainerContext(procDmc, groupId);
								processes.getProcessesBeingDebugged(containerDmc,
										new ImmediateDataRequestMonitor<IDMContext[]>() {
											@Override
											protected void handleSuccess() {
												List<IRunControl.IExecutionDMContext> execs = new ArrayList<>();
												for (IDMContext ctx : getData()) {
													if (ctx instanceof IRunControl.IExecutionDMContext exec) {
														execs.add(exec);
													}
												}
												if (execs.isEmpty()) {
													rm.setStatus(Status.error("No execution context to evaluate in."));
													rm.done();
													return;
												}
												IRunControl.IExecutionDMContext exec = execs
														.get(Math.min(threadIndex, execs.size() - 1));
												evaluateInFrame(stack, commandControl, commandFactory, exec,
														expression, rm);
											}

											@Override
											protected void handleFailure() {
												rm.setStatus(getStatus());
												rm.done();
											}
										});
							}
						});
			}
		};
		launch.getDsfExecutor().execute(query);
		try {
			return query.get(3, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new CoreException(Status.error("Evaluating the expression failed: " + e.getMessage()));
		}
	}

	/** Issues -data-evaluate-expression on a resolved frame and builds the result JSON. */
	private static void evaluateInFrame(IStack stack, ICommandControlService commandControl,
			CommandFactory commandFactory, IRunControl.IExecutionDMContext exec, String expression,
			DataRequestMonitor<JsonObject> rm) {
		stack.getTopFrame(exec,
				new ImmediateDataRequestMonitor<IStack.IFrameDMContext>() {
					@Override
					protected void handleSuccess() {
						IStack.IFrameDMContext top = getData();
						commandControl.queueCommand(
								commandFactory.createMIDataEvaluateExpression(top, expression),
								new ImmediateDataRequestMonitor<MIDataEvaluateExpressionInfo>() {
									@Override
									protected void handleCompleted() {
										MIDataEvaluateExpressionInfo info = getData();
										boolean ok = getStatus() != null && getStatus().isOK();
										JsonObject result = new JsonObject();
										result.put("expression", expression);
										result.put("value", info != null ? safeText(info::getValue) : "");
										result.put("success", ok);
										if (!ok) {
											result.put("error", getStatus() != null ? getStatus().getMessage() : "evaluate failed");
										}
										stack.getFrameData(top,
												new ImmediateDataRequestMonitor<IStack.IFrameDMData>() {
													@Override
													protected void handleCompleted() {
														IStack.IFrameDMData data = getData();
														if (data != null) {
															result.put("function", safeText(data::getFunction));
															result.put("file", safeText(data::getFile));
															result.put("line", data.getLine());
														}
														rm.done(result);
													}
												});
									}
								});
					}

					@Override
					protected void handleFailure() {
						rm.setStatus(getStatus());
						rm.done();
					}
				});
	}

	/**
	 * Reads the full call stack of a suspended DSF thread (function/file/line per
	 * frame) and, optionally, each frame's locals and arguments. Runs on the DSF
	 * executor and blocks briefly.
	 */
	public static JsonObject framesDsf(GdbLaunch launch, int threadIndex, boolean includeVariables, int maxFrames)
			throws CoreException {
		DsfSession session = launch.getSession();
		if (session == null || !session.isActive()) {
			throw new CoreException(Status.error("The DSF session is not active."));
		}
		Query<JsonObject> query = new Query<>() {
			@Override
			protected void execute(DataRequestMonitor<JsonObject> rm) {
				DsfServicesTracker tracker = servicesTracker(session);
				IProcesses processes = tracker.getService(IProcesses.class);
				IRunControl runControl = tracker.getService(IRunControl.class);
				IStack stack = tracker.getService(IStack.class);
				ICommandControlService commandControl = tracker.getService(ICommandControlService.class);
				IMICommandControl miCommandControl = tracker.getService(IMICommandControl.class);
				CommandFactory commandFactory = miCommandControl != null ? miCommandControl.getCommandFactory()
						: new CommandFactory();
				IMIProcesses miProcesses = tracker.getService(IMIProcesses.class);
				tracker.dispose();
				if (processes == null || runControl == null || stack == null || commandControl == null
						|| miProcesses == null) {
					rm.setStatus(Status.error("DSF services not available yet."));
					rm.done();
					return;
				}
				commandControl.queueCommand(commandFactory.createMIListThreadGroups(commandControl.getContext()),
						new ImmediateDataRequestMonitor<MIListThreadGroupsInfo>() {
							@Override
							protected void handleCompleted() {
								MIListThreadGroupsInfo info = getData();
								String groupId = info != null && info.getGroupList() != null
										&& info.getGroupList().length > 0
												? info.getGroupList()[0].getGroupId()
												: null;
								if (groupId == null) {
									rm.setStatus(Status.error("gdb reports no thread-group to read frames from."));
									rm.done();
									return;
								}
								IProcesses.IProcessDMContext procDmc = miProcesses
										.createProcessContext(commandControl.getContext(), groupId);
								IRunControl.IContainerDMContext containerDmc = miProcesses
										.createContainerContext(procDmc, groupId);
								processes.getProcessesBeingDebugged(containerDmc,
										new ImmediateDataRequestMonitor<IDMContext[]>() {
											@Override
											protected void handleSuccess() {
												List<IRunControl.IExecutionDMContext> execs = new ArrayList<>();
												for (IDMContext ctx : getData()) {
													if (ctx instanceof IRunControl.IExecutionDMContext exec) {
														execs.add(exec);
													}
												}
												if (execs.isEmpty()) {
													rm.setStatus(Status.error("No execution context to read frames from."));
													rm.done();
													return;
												}
												IRunControl.IExecutionDMContext exec = execs
														.get(Math.min(threadIndex, execs.size() - 1));
												collectFrames(stack, commandControl, commandFactory, exec,
														includeVariables, maxFrames, rm);
											}

											@Override
											protected void handleFailure() {
												rm.setStatus(getStatus());
												rm.done();
											}
										});
							}
						});
			}
		};
		launch.getDsfExecutor().execute(query);
		try {
			return query.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new CoreException(Status.error("Reading the call stack failed: " + e.getMessage()));
		}
	}

	/** Requests all frames, then each frame's data (and variables), converging via a pending counter. */
	private static void collectFrames(IStack stack, ICommandControlService commandControl,
			CommandFactory commandFactory, IRunControl.IExecutionDMContext exec, boolean includeVariables,
			int maxFrames, DataRequestMonitor<JsonObject> rm) {
		stack.getFrames(exec, 0, maxFrames <= 0 ? IStack.ALL_FRAMES : maxFrames,
				new ImmediateDataRequestMonitor<IStack.IFrameDMContext[]>() {
					@Override
					protected void handleSuccess() {
						IStack.IFrameDMContext[] frames = getData();
						JsonObject result = new JsonObject();
						result.put("thread", String.valueOf(exec));
						// Keep frame objects in an ordered list; JsonArray has no get(int),
						// so assemble the array only after all async reads complete.
						List<JsonObject> slotList = new ArrayList<>();
						for (int i = 0; i < frames.length; i++) {
							slotList.add(new JsonObject());
						}
						AtomicInteger pending = new AtomicInteger(frames.length);
						if (frames.length == 0) {
							result.put("frames", new JsonArray());
							result.put("total", 0);
							rm.done(result);
							return;
						}
						for (int i = 0; i < frames.length; i++) {
							readFrame(stack, commandControl, commandFactory, frames[i], slotList.get(i),
									includeVariables,
									() -> {
										if (pending.decrementAndGet() == 0) {
											JsonArray arr = new JsonArray();
											for (JsonObject f : slotList) {
												arr.add(f);
											}
											result.put("frames", arr);
											result.put("total", frames.length);
											rm.done(result);
										}
									});
						}
					}

					@Override
					protected void handleFailure() {
						rm.setStatus(getStatus());
						rm.done();
					}
				});
	}

	/** Reads one frame's function/file/line and, optionally, locals + arguments via MI. */
	private static void readFrame(IStack stack, ICommandControlService commandControl,
			CommandFactory commandFactory, IStack.IFrameDMContext frame, JsonObject target,
			boolean includeVariables, Runnable onDone) {
		stack.getFrameData(frame, new ImmediateDataRequestMonitor<IStack.IFrameDMData>() {
			@Override
			protected void handleCompleted() {
				IStack.IFrameDMData data = getData();
				target.put("level", frame.getLevel());
				if (data != null) {
					target.put("function", safeText(data::getFunction));
					target.put("file", safeText(data::getFile));
					target.put("line", data.getLine());
					target.put("module", safeText(data::getModule));
				}
				if (!includeVariables) {
					onDone.run();
					return;
				}
				JsonArray vars = new JsonArray();
				target.put("variables", vars);
				// IStack.getLocals/getArguments often come back empty on this DSF
				// target; the Variables view uses MI -stack-list-locals/-arguments
				// instead. Emit both, converge with a shared counter.
				AtomicInteger varPending = new AtomicInteger(2);
				commandControl.queueCommand(commandFactory.createMIStackListLocals(frame, true),
						new ImmediateDataRequestMonitor<MIStackListLocalsInfo>() {
							@Override
							protected void handleCompleted() {
								MIStackListLocalsInfo info = isSuccess() ? getData() : null;
								MIArg[] locals = info != null ? info.getLocals() : null;
								if (locals != null) {
									for (MIArg a : locals) {
										vars.add(varJson(a, "local"));
									}
								}
								if (varPending.decrementAndGet() == 0) {
									onDone.run();
								}
							}
						});
				commandControl.queueCommand(commandFactory.createMIStackListArguments(frame, true),
						new ImmediateDataRequestMonitor<MIStackListArgumentsInfo>() {
							@Override
							protected void handleCompleted() {
								MIStackListArgumentsInfo info = isSuccess() ? getData() : null;
								MIFrame[] miFrames = info != null ? info.getMIFrames() : null;
								if (miFrames != null) {
									for (MIFrame mf : miFrames) {
										MIArg[] args = mf.getArgs();
										if (args != null) {
											for (MIArg a : args) {
												vars.add(varJson(a, "argument"));
											}
										}
									}
								}
								if (varPending.decrementAndGet() == 0) {
									onDone.run();
								}
							}
						});
			}

			@Override
			protected void handleFailure() {
				onDone.run();
			}
		});
	}

	private static JsonObject varJson(MIArg arg, String kind) {
		JsonObject one = new JsonObject();
		one.put("name", safeText(arg::getName));
		one.put("value", safeText(arg::getValue));
		one.put("kind", kind);
		return one;
	}

	/**
	 * Breakpoint CRUD over GDB/MI (-break-insert/-delete/-list/-condition/-enable/-disable).
	 * Runs on the DSF executor and blocks briefly.
	 */
	public static JsonObject breakpointsDsf(GdbLaunch launch, String action, String location,
			String condition, String ids, boolean temporary, boolean hardware, int ignoreCount)
			throws CoreException {
		DsfSession session = launch.getSession();
		if (session == null || !session.isActive()) {
			throw new CoreException(Status.error("The DSF session is not active."));
		}
		Query<JsonObject> query = new Query<>() {
			@Override
			protected void execute(DataRequestMonitor<JsonObject> rm) {
				DsfServicesTracker tracker = servicesTracker(session);
				ICommandControlService commandControl = tracker.getService(ICommandControlService.class);
				IMICommandControl miCommandControl = tracker.getService(IMICommandControl.class);
				tracker.dispose();
				if (commandControl == null || miCommandControl == null) {
					rm.setStatus(Status.error("DSF command control not available yet."));
					rm.done();
					return;
				}
				CommandFactory commandFactory = miCommandControl.getCommandFactory();
				IDMContext controlContext = commandControl.getContext();
				if (!(controlContext instanceof IBreakpoints.IBreakpointsTargetDMContext target)) {
					rm.setStatus(Status.error("Command control context is not a breakpoints target: " + controlContext));
					rm.done();
					return;
				}
				JsonObject result = new JsonObject();
				result.put("action", action);
				switch (action) {
					case "list" -> commandControl.queueCommand(commandFactory.createMIBreakList(target),
							new ImmediateDataRequestMonitor<MIBreakListInfo>() {
								@Override
								protected void handleCompleted() {
									MIBreakListInfo info = isSuccess() ? getData() : null;
									MIBreakpoint[] bps = info != null ? info.getMIBreakpoints() : null;
									JsonArray arr = new JsonArray();
									if (bps != null) {
										for (MIBreakpoint b : bps) {
											arr.add(breakpointJson(b));
										}
									}
									result.put("breakpoints", arr);
									result.put("success", isSuccess());
									rm.done(result);
								}
							});
					case "set" -> {
						if (location == null || location.isBlank()) {
							rm.setStatus(Status.error("action=set requires 'location'."));
							rm.done();
							return;
						}
						commandControl.queueCommand(commandFactory.createMIBreakInsert(target,
								temporary, hardware, condition, ignoreCount, location, "0"),
								new ImmediateDataRequestMonitor<MIBreakInsertInfo>() {
									@Override
									protected void handleCompleted() {
										MIBreakInsertInfo info = isSuccess() ? getData() : null;
										MIBreakpoint[] bps = info != null ? info.getMIBreakpoints() : null;
										JsonArray arr = new JsonArray();
										if (bps != null) {
											for (MIBreakpoint b : bps) {
												arr.add(breakpointJson(b));
											}
										}
										result.put("breakpoints", arr);
										result.put("success", isSuccess());
										if (!isSuccess() && getStatus() != null) {
											result.put("error", getStatus().getMessage());
										}
										rm.done(result);
									}
								});
					}
					case "delete", "enable", "disable" -> {
						if (ids == null || ids.isBlank()) {
							rm.setStatus(Status.error("action=" + action + " requires 'ids' (comma separated breakpoints)."));
							rm.done();
							return;
						}
						String[] idArray = ids.split("[,\\s]+");
						ICommand<MIInfo> command = switch (action) {
							case "delete" -> commandFactory.createMIBreakDelete(target, idArray);
							case "enable" -> commandFactory.createMIBreakEnable(target, idArray);
							default -> commandFactory.createMIBreakDisable(target, idArray);
						};
						commandControl.queueCommand(command, new ImmediateDataRequestMonitor<MIInfo>() {
							@Override
							protected void handleCompleted() {
								result.put("ids", ids);
								result.put("success", isSuccess());
								if (!isSuccess() && getStatus() != null) {
									result.put("error", getStatus().getMessage());
								}
								rm.done(result);
							}
						});
					}
					case "condition" -> {
						if (ids == null || ids.isBlank() || condition == null || condition.isBlank()) {
							rm.setStatus(Status.error("action=condition requires 'ids' and 'condition'."));
							rm.done();
							return;
						}
						commandControl.queueCommand(commandFactory.createMIBreakCondition(target, ids, condition),
								new ImmediateDataRequestMonitor<MIInfo>() {
									@Override
									protected void handleCompleted() {
										result.put("id", ids);
										result.put("condition", condition);
										result.put("success", isSuccess());
										if (!isSuccess() && getStatus() != null) {
											result.put("error", getStatus().getMessage());
										}
										rm.done(result);
									}
								});
					}
					default -> {
						rm.setStatus(Status.error("Unknown action '" + action
								+ "'; use list/set/delete/enable/disable/condition."));
						rm.done();
					}
				}
			}
		};
		launch.getDsfExecutor().execute(query);
		try {
			return query.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new CoreException(Status.error("Breakpoint operation failed: " + e.getMessage()));
		}
	}

	private static JsonObject breakpointJson(MIBreakpoint b) {
		JsonObject o = new JsonObject();
		o.put("id", safeText(b::getNumber));
		o.put("type", safeText(b::getType));
		o.put("enabled", b.isEnabled());
		o.put("address", safeText(b::getAddress));
		o.put("function", safeText(b::getFunction));
		o.put("file", safeText(b::getFile));
		o.put("line", b.getLine());
		o.put("condition", safeText(b::getCondition));
		o.put("ignoreCount", b.getIgnoreCount());
		o.put("hitCount", b.getTimes());
		o.put("pending", b.isPending());
		return o;
	}

	/** Reads raw bytes from a target address via GDB/MI -data-read-memory-bytes. */
	public static JsonObject readMemoryDsf(GdbLaunch launch, String address, long offset, int length)
			throws CoreException {
		final long base;
		try {
			base = address != null && (address.startsWith("0x") || address.startsWith("0X"))
					? Long.parseUnsignedLong(address.substring(2), 16)
					: Long.parseLong(address);
		} catch (Exception e) {
			throw new CoreException(Status.error("Invalid address '" + address + "': " + e.getMessage()));
		}
		DsfSession session = launch.getSession();
		if (session == null || !session.isActive()) {
			throw new CoreException(Status.error("The DSF session is not active."));
		}
		Query<JsonObject> query = new Query<>() {
			@Override
			protected void execute(DataRequestMonitor<JsonObject> rm) {
				DsfServicesTracker tracker = servicesTracker(session);
				ICommandControlService commandControl = tracker.getService(ICommandControlService.class);
				IMICommandControl miCommandControl = tracker.getService(IMICommandControl.class);
				tracker.dispose();
				if (commandControl == null || miCommandControl == null) {
					rm.setStatus(Status.error("DSF command control not available yet."));
					rm.done();
					return;
				}
				CommandFactory commandFactory = miCommandControl.getCommandFactory();
				commandControl.queueCommand(
						commandFactory.createMIDataReadMemoryBytes(commandControl.getContext(), address, offset, length),
						new ImmediateDataRequestMonitor<MIDataReadMemoryBytesInfo>() {
							@Override
							protected void handleCompleted() {
								MIDataReadMemoryBytesInfo info = isSuccess() ? getData() : null;
								MemoryByte[] bytes = info != null ? info.getMIMemoryBlock() : null;
								JsonObject result = new JsonObject();
								result.put("address", address);
								result.put("offset", offset);
								result.put("length", length);
								result.put("success", isSuccess());
								JsonArray hex = new JsonArray();
								StringBuilder ascii = new StringBuilder();
								StringBuilder dump = new StringBuilder();
								if (bytes != null) {
									for (int i = 0; i < bytes.length; i++) {
										int v = bytes[i].getValue() & 0xFF;
										hex.add(String.format("%02x", v));
										ascii.append(v >= 0x20 && v < 0x7f ? (char) v : '.');
										if (i % 16 == 0) {
											if (i > 0) {
												dump.append('\n');
											}
											dump.append(String.format("%08x  ", base + offset + i));
										}
										dump.append(String.format("%02x ", v));
										if (i % 16 == 15 || i == bytes.length - 1) {
											int lastInRow = i % 16;
											for (int p = lastInRow; p < 15; p++) {
												dump.append("   ");
											}
											dump.append(" |");
											for (int j = i - lastInRow; j <= i; j++) {
												int bv = bytes[j].getValue() & 0xFF;
												dump.append(bv >= 0x20 && bv < 0x7f ? (char) bv : '.');
											}
											dump.append('|');
										}
									}
								}
								result.put("bytes", hex);
								result.put("ascii", ascii.toString());
								result.put("hexdump", dump.toString());
								if (!isSuccess() && getStatus() != null) {
									result.put("error", getStatus().getMessage());
								}
								rm.done(result);
							}
						});
			}
		};
		launch.getDsfExecutor().execute(query);
		try {
			return query.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new CoreException(Status.error("Reading memory failed: " + e.getMessage()));
		}
	}

	private interface TextSupplier {
		String get() throws Exception;
	}

	private static String safeText(TextSupplier supplier) {
		try {
			return supplier.get();
		} catch (Exception e) {
			return "";
		}
	}
}
