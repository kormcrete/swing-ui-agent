---
title: Agent options
description: The options after -javaagent:swing-ui-agent.jar= and the system properties tests read.
sidebar:
  order: 3
---

Agent options go after `=`, comma-separated:

```sh
java -javaagent:swing-ui-agent.jar=port=8765,bind=127.0.0.1,mode=normal,plugins=a.jar:b/classes -jar my-app.jar
```

| Option | Default | What it does |
| --- | --- | --- |
| `port` | `8765` | Where the CLI, tests and MCP clients connect. |
| `bind` | `127.0.0.1` | The address to listen on. Loopback only. |
| `mode` | `normal` | `normal` or `background`. On macOS, `background` keeps the app out of the Dock so it never takes the foreground; elsewhere both behave the same. On every platform the app can be driven while other windows are in front. |
| `plugins` | none | Plugin jars or class folders, separated by `:`. See [What a plugin adds](/swing-ui-agent/plugins/what-a-plugin-adds/). |

## System properties

| Property | Set on | What it does |
| --- | --- | --- |
| `swingui.testsDir` | the app | Where `export_test` writes tests. Default: `src/test/java` under the app's working directory. |
| `swingui.port` | the tests | The agent port a test connects to. Default `8765`. |
