# STM32CubeIDE MCP Server

A fork of [vogella's Eclipse MCP Server](https://github.com/vogellacompany/eclipse-mcp-server), adapted for **STM32CubeIDE** and embedded STM32/Cortex-M development. It turns a running STM32CubeIDE into an [MCP](https://modelcontextprotocol.io) server so an LLM client can ask the IDE for the things it cannot reconstruct from files alone: the CDT C/C++ index, build problems, the live debug session on the target, and CubeIDE's firmware project model.

The server is **disabled by default**, binds `127.0.0.1` only, and rejects requests without the bearer token. Most tools are read-only; tools that write say so in their own description and default to a dry run where one makes sense.

## What is different from upstream

This fork is deliberately narrowed to embedded work:

- **Removed** the `jdt`, `debug`, `pde`, `git`, `ide` and `p2` bundles from the reactor and feature.
- **Added** two provider bundles: `com.vogella.eclipse.mcp.cdt` (CDT index navigation for C/C++) and `com.vogella.eclipse.mcp.stm32` (CubeIDE launch/flash, DSF/GDB debug control, register and memory access, CubeMX project info).
- Kept `core`, `basic`, `server` and `ui`. The 55 tools fall into the `eclipse_*`, `cdt_*`, `cubeide_*`, `stm32_*` groups plus `mcp_hot_reload` (see [Tools](#tools)).
- Verified against **STM32CubeIDE V2** with a J-Link probe on SWD. The maintainers' target board is a KZ036 carrying a GD32F450 used in place of the STM32F429ZI; the ST launch plug-in has a local, intentional vendor-check stub.

## Requirements

- **STM32CubeIDE V2** (Eclipse 4.38 / CDT 12.3 / Java 17-era platform).
- **JDK 25** for Maven and for running the IDE's MCP bundles. CubeIDE ships its own JRE 21; point `stm32cubeide.ini`'s `-vm` at a JDK 25 `java.exe` (keep a backup of the `.ini`). Without this the Java 25 bundles do not resolve.
- **Maven 3.9+** (Tycho needs a recent Maven for pomless builds).
- A debug probe supported by the IDE's ST launch configuration (J-Link on SWD is what is exercised here).

## Building

From the repository root (`server/`):

```bash
mvn clean verify
```

The p2 repository is produced at `update-site/com.vogella.eclipse.mcp.repository/target/repository`. Always build from the root: a module directory lacks the sibling bundles in the reactor, and `-pl` on one bundle still resolves the whole target platform, so there is no faster partial build.

To work on the code in Eclipse, import the projects with *Search for nested projects* enabled and set `target-platform/com.vogella.eclipse.mcp.target/com.vogella.eclipse.mcp.target.target` as the active target platform.

The Tycho test bundles under `tests/` are **not** part of this build's reactor (they still declare dependencies on bundles this fork removed). Build verification therefore covers the main code; behaviour is checked with the end-to-end smoke suite described below.

## Installing and updating

Install the six MCP bundles directly into the CubeIDE installation with the p2 director. From the workspace root:

```bash
python scripts/install_mcp.py
```

The IDE must be **closed** while this runs. It uninstalls the previously registered MCP IUs, removes orphan jars from the IDE's `plugins/` directory, and installs the freshly built bundles. Restart the IDE afterwards.

For day-to-day development, `mcp_hot_reload` updates a built bundle jar in the running IDE without a restart:

```
mcp_hot_reload(jar="E:/.../server/plugins/com.vogella.eclipse.mcp.stm32/target/com.vogella.eclipse.mcp.stm32-3.2.0-SNAPSHOT.jar", dryRun=false)
```

Hot reload is **not persistent**: p2 restores the installed version on the next IDE restart. To make a change permanent, rebuild and run `install_mcp.py`.

## Enabling and connecting

Enable the server in *Preferences > General > MCP Server* (off by default; port `8642`, 30 s call timeout, 25 s UI wait). The page also shows the bearer token and the discovery file, and can regenerate the token.

The server writes its endpoint to:

```
<workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json
```

```json
{
  "state": "listening",
  "url": "http://127.0.0.1:8642/mcp",
  "token": "0f0f2a2e-1f9c-4c4a-9a0e-6d0f8f0f1e2b",
  "workspace": "/path/to/workspace"
}
```

Connect an MCP client to that URL with `Authorization: Bearer <token>`. For Claude Code:

```bash
claude mcp add --transport http cubeide http://127.0.0.1:8642/mcp \
  --header "Authorization: Bearer $(jq -r .token <workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json)"
```

The transport is Streamable HTTP; every request carries the bearer token and anything else gets a `401`. The token lives in `~/.eclipse/com.vogella.eclipse.mcp.server/token` with owner-only permissions, shared across workspaces. Several clients may share one server; after an IDE restart the client must reconnect and re-read `endpoint.json`, because a fresh start may bind a different port.

Tools only: `initialize`, `ping`, `tools/list`, `tools/call`. Every tool returns JSON, and every list-returning tool honours `maxResults` and reports `total` and `truncated`.

## Tools

55 tools are exposed. ✎ marks a tool that changes something.

**Workspace, build and IDE basics (`eclipse_*`)**

| Tool | Purpose |
|---|---|
| `eclipse_list_projects`, `eclipse_resolve_path`, `eclipse_get_project_dependencies` | Project inventory, path mapping, resolved build path |
| `eclipse_read_file`, `eclipse_search_text`, `eclipse_read_image` | Read files (optionally a dirty editor buffer), search text, read an image from a project |
| ✎ `eclipse_write_file`, `eclipse_edit_file` | Write and edit files, keeping the previous content in local history |
| `eclipse_get_local_history`, ✎ `eclipse_restore_local_history` | List and restore Eclipse's file history |
| ✎ `eclipse_refresh`, `eclipse_save_workspace`, `eclipse_set_project_state`, `eclipse_remove_project` | Workspace housekeeping |
| `eclipse_get_problems`, `eclipse_mark_problems` | Compiler markers with source-set filtering and a comparison baseline |
| ✎ `eclipse_build`, `eclipse_cancel_build`, `eclipse_get_build_status`, `eclipse_wait_until_quiet` | Build as a job, poll, cancel, wait for quiet |
| `eclipse_get_log_entries`, `eclipse_mark_log`, ✎ `eclipse_clear_log`, `eclipse_log_status` | Error Log with byte markers, stack paging, clear and write |
| `eclipse_get_preferences`, ✎ `eclipse_set_preference` | Preferences with scope and batch read/write; writes report previous values for rollback |
| ✎ `eclipse_run_command`, `eclipse_get_command_output`, `eclipse_run_script` | Shell command with output polling; scripted tool sequences with expectations, `found` and resume cursor |
| `eclipse_get_startup_timing` | Observed startup phases and bundle activation times |
| ✎ `eclipse_install_bundle`, `eclipse_substitute_bundle` | Live bundle install and workspace-bundle substitution |
| ✎ `eclipse_start_flight_recording`, `eclipse_stop_flight_recording` | JFR recording and analysis |

**C/C++ navigation (`cdt_*`, CDT index)**

| Tool | Purpose |
|---|---|
| `cdt_find_symbols` | Find symbol definitions by name, with path and line |
| `cdt_open_symbol` | Resolve a symbol to its definition and surrounding source snippet |
| `cdt_outline` | Declarations in a C/C++ file, including macros with approximate-location notes |
| `cdt_type_members` | Fields of a struct/union/class or enumerators of an enum |
| `cdt_find_references` | Call sites and uses of a symbol, with line text |
| `cdt_call_hierarchy` | Callers and callees of a function, recursively |

**CubeIDE project and CubeMX (`cubeide_*`)**

| Tool | Purpose |
|---|---|
| `cubeide_project_info` | MCU, firmware package, clock, enabled peripherals and active build configuration from `.ioc`/`.cproject` |
| `cubeide_regenerate` | `dryRun` impact analysis or start a CubeMX regeneration; real runs open CubeMX, they do not drive its Generate button |

**STM32 debug and flash (`stm32_*`)**

| Tool | Purpose |
|---|---|
| `stm32_list_launch_configurations` | Workspace launch configurations, their type, modes and `.launch` file |
| `stm32_debug_delegate_check` | Diagnose a launch stuck in `launching` by running the delegate's checks step by step |
| ✎ `stm32_debug_launch` | Start a launch configuration; `mode=debug` builds, flashes and stops in the debugger |
| `stm32_debug_control` | Sessions/status and `continue`, `pause`, step and terminate through DSF/GDB |
| `stm32_debug_evaluate` | Evaluate an expression, optionally at a given stack frame |
| `stm32_debug_frames` | Call stack with locals, from the innermost frame |
| `stm32_debug_breakpoints` | List, insert, delete, condition, enable and disable GDB breakpoints |
| `stm32_debug_read_memory`, `stm32_memory_dump`, `stm32_sfr_read` | Raw bytes by address, dump by symbol or address, and peripheral registers |
| ✎ `stm32_build_and_flash`, `stm32_flash_status` | Build and flash by starting a launch configuration; poll the result |

**Server maintenance**

| Tool | Purpose |
|---|---|
| ✎ `mcp_hot_reload` | Update MCP bundle jars into the running IDE without a restart |

## Debug session notes

STM32CubeIDE launches through CDT DSF/GDB, which has no classic `IDebugTarget`. Session state is read by querying GDB/MI directly and building DM contexts, with per-session caches for the thread-group id and last-seen execution contexts; this is what lets `continue` report `running` and lets `pause` steer a running target even when the IDE's container cache is stale. A launch call has side effects: `mode=debug` builds the project and flashes the target. Prefer read-only status calls before steering, and terminate sessions when done.

A launch can leave a session stuck in `launching` if the IDE's launch worker deadlocks against a UI-thread view refresh. Such a session has no thread group and cannot be steered by `stm32_debug_control`; `stm32_debug_delegate_check` and the IDE's Error Log help confirm it. Use the IDE's own Suspend button, or attach J-Link GDB with `monitor halt` (read-only, do not reset) to inspect a running target.

## Smoke tests

The end-to-end suite lives in the workspace `scripts/` directory (outside this git repository) and drives a running IDE over MCP:

```bash
python scripts/run_smoke.py            # all cases
python scripts/run_smoke.py --only t21 # one case
python scripts/run_smoke.py --list
```

It shares one MCP session across cases, prints a PASS/FAIL line with the observed values for each assertion, sums them up, and exits non-zero on any failure. Cases cover the STM32 debug lifecycle (`t21`, `t22`), the CDT/nav and CubeIDE tools, and regressions such as launch-configuration counts, macro-location notes and `cubeide_regenerate dryRun` git status. Each case starts from a fresh debug session and terminates it afterwards; some write tools use dedicated qualifiers and roll back.

## Bundles

| Bundle | Contains |
|---|---|
| `com.vogella.eclipse.mcp.core` | Tool API, registry, extension point, shared infrastructure; no MCP, Jetty or UI dependency |
| `com.vogella.eclipse.mcp.basic` | Workspace, file, build, preference, log, command and related tools |
| `com.vogella.eclipse.mcp.server` | MCP protocol, embedded Jetty, bearer token, discovery file, hot reload |
| `com.vogella.eclipse.mcp.ui` | Preference page and UI hooks; optional for a headless server |
| `com.vogella.eclipse.mcp.cdt` | CDT index navigation for C/C++ |
| `com.vogella.eclipse.mcp.stm32` | CubeIDE launch/flash, DSF/GDB debug control, memory and SFR access, CubeMX project info |

## Contributing a tool

Tools are contributed through the `com.vogella.eclipse.mcp.core.tools` extension point:

```xml
<extension point="com.vogella.eclipse.mcp.core.tools">
   <tool class="com.example.MyTool"/>
</extension>
```

`com.example.MyTool` implements `com.vogella.eclipse.mcp.core.IMcpTool`. It runs on a worker thread, must not open a dialog, must mark itself as writing in its description, and must finish within the call timeout or return a handle to poll. See `AGENTS.md` for the full rules and gotchas.

## License

Same terms as the upstream fork; see `LICENSE.md`.
