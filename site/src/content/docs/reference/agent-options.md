---
title: Agent options
description: The options after -javaagent:swing-ui-agent.jar= and the system properties the app and the tests read.
sidebar:
  order: 3
---

Agent options go after `=`, comma-separated:

```sh
java -javaagent:swing-ui-agent.jar=port=8765,bind=127.0.0.1,mode=normal,inspector=off,plugins=a.jar:b/classes -jar my-app.jar
```

| Option | Default | What it does |
| --- | --- | --- |
| `port` | `8765` | Where the CLI, tests and MCP clients connect. `0` picks a free port; the agent prints the one it got. |
| `bind` | `127.0.0.1` | The address to listen on: `127.0.0.1`, `::1` or `localhost`. Any other address is refused. |
| `mode` | `normal` | `normal` or `background`. On macOS, `background` keeps the app out of the Dock so it never takes the foreground; elsewhere both behave the same. On every platform the app can be driven while other windows are in front. |
| `inspector` | `off` | `on` puts a small palette into the app window for a person at the screen: they press ● Record, use the app, add checks and replay, and the agent reads and exports the recording. The agent can also box what it means and let them confirm or pick. Off for agent-driven and CI runs: no palette, nothing listens to input. Not with `mode=background`. See [Record a test by showing it](/swing-ui-agent/start/show/). |
| `plugins` | none | Plugin jars or class folders, separated by `:` (`;` on Windows). See [What a plugin adds](/swing-ui-agent/plugins/what-a-plugin-adds/). |

On start the agent prints `swing-ui-agent listening on http://127.0.0.1:8765/mcp`. A bad option is printed on stderr instead, and the app runs without the agent.

## System properties

Set them with `-D` on the JVM named in the second column.

| Property | Set on | What it does |
| --- | --- | --- |
| `swingui.testsDir` | the app | Where `export_test` writes tests. Default: a plugin's module `src/test/java` when it exists, else `src/test/java` under the app's working directory. |
| `swingui.screenshotDir` | the app | Where a relative path in a recorded `screenshot` step goes when the agent runs it. Default: `target/flow-screenshots` under the plugin's module, else under the working directory. |
| `swingui.timingLog` | the app | A file the agent appends one timing line to per tool call. Default: `.swing-ui-agent/timing.log`, only when the working directory has a `.swing-ui-agent/` folder. `off` turns it off. |
| `swingui.users` | the app and the tests | The login aliases and secrets file. Default: `.swing-ui-agent/ui-users.properties` in the working directory or the nearest folder above it that has one. |
| `swingui.port` | the tests | The agent port a test connects to. Default `8765`. |
| `swingui.agents` | the tests | Several agents for tests that drive more than one app, as `host:port,host:port`. A JUnit configuration parameter: a system property, `junit-platform.properties` or a suite's `@ConfigurationParameter`. Default: the one agent on `swingui.port`. |
| `swingui.poolWaitMs` | the tests | How long a test waits for another test in the same JVM to give back the agents it needs. Default `600000` (10 minutes). |
| `swingui.leaseWaitMs` | the tests | How long a test waits for a busy agent's lease. Default `16000`, just past the 15 seconds after which an idle lease can be taken over. |

A test saves `ui.saveScreenshot("…")` with a relative path under its module's `target/ui-screenshots`, writes its run report to `target/ui-runs/latest.txt`, and leaves failure evidence under `target/ui-failures`. None of these needs a property.
