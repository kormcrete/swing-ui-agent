---
title: CLI or MCP?
description: Both give an agent the same tools. Pick by the agent you use.
sidebar:
  order: 1
---

The agent in your app serves one set of tools. A coding agent reaches them in one of two ways:

- **the command line:** the agent jar, one shell call per tool;
- **MCP:** streamable HTTP at `http://127.0.0.1:8765/mcp`.

The tools, their arguments and their answers are the same either way.

## Use the CLI when your agent has a shell

This is the main path for coding agents.

```sh
UI="java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar --port 8765"
$UI session
$UI snapshot '{"window":"main","depth":3,"compact":true}'
$UI menu '{"path":["File","New order"]}'
```

- **Nothing to set up.** No client configuration: the agent runs the jar like any other command.
- **Nothing to reconnect.** Each call connects, runs one tool and exits. Restart or rebuild the app, and the next call just works.
- **Plain answers.** One JSON answer on stdout, an error on stderr, and an [exit code](/swing-ui-agent/reference/cli/) the agent can branch on: `3` means no app with the agent is running.
- **Files instead of images.** `--out orders.png screenshot` writes the PNG to a file, so the agent opens it only when it needs to.
- **The skills assume it.** The three [skills](/swing-ui-agent/agents/skills/) show every step as a CLI call.

Each call starts a small JVM. Driving a whole flow is many calls, so use `batch` when the next steps are already known: several steps, one call.

## Use MCP when your agent speaks it

Point any MCP client that supports streamable HTTP at `http://127.0.0.1:8765/mcp`. The client lists the tools itself and calls them as tool calls. Setup for a few clients is in [MCP clients](/swing-ui-agent/agents/mcp-clients/).

- **No shell needed.** For agents and chat clients without one.
- **Images inline.** `screenshot` returns the PNG as image content.
- **Instructions on connect.** The client receives a short description of how to read and act on the app.

The endpoint is stateless: each request is one JSON-RPC message, answered in the response. There is no MCP session to keep alive.

## Both at once

The CLI, MCP clients and running tests all talk to the same agent, so they can be mixed. One owner acts at a time: while a test run or a script holds the [lease](/swing-ui-agent/guides/leases/), others can still read, but not act.

## What's next

- [Let your agent drive the app](/swing-ui-agent/start/drive/): a worked session on Order Desk.
- [Skills](/swing-ui-agent/agents/skills/): teach your coding agent the workflow.
- [Tools](/swing-ui-agent/reference/tools/): every tool and its arguments.
