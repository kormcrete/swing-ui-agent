---
title: MCP clients
description: Connect any MCP client that speaks streamable HTTP.
sidebar:
  order: 4
---

The agent serves its tools over MCP's streamable HTTP transport at `http://127.0.0.1:8765/mcp`. Any MCP client that supports this transport can use them, with no adapter in between.

## Before you connect

1. Start your app with the agent (see [Installation](/swing-ui-agent/start/install/)).
2. Check that it answers:

   ```sh
   java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar session
   ```

   Exit code `0` and a JSON answer mean the agent is up. With `port=` set, pass the same `--port`, and use that port in the URL below.

## Connect a client

In Claude Code, add the server once per project:

```sh
claude mcp add --transport http swing-ui http://127.0.0.1:8765/mcp
```

Other clients take the same URL in their settings. Where a client asks for a JSON entry, it usually looks like this (the exact keys depend on the client):

```json
{
  "mcpServers": {
    "swing-ui": { "type": "http", "url": "http://127.0.0.1:8765/mcp" }
  }
}
```

The client then lists the tools (`snapshot`, `click`, `expect`, `recording`, `export_recorded_test`, `run_recorded_test`, `export_test` and the rest) and receives short server instructions on how to read and act on the app.

## Recording and recorded tests over MCP

- **Record:** call `recording` with `{"op":"start","reset":true}`, act with the usual tools, then `{"op":"stop"}` and `export_recorded_test`. The person at the app sees the steps in the palette and can change them. See [Record a test together with your agent](/swing-ui-agent/start/record-together/).
- **Run:** `run_recorded_test` with a `name`, a `path`, or the file's JSON as `test`. The answer carries `passed`, `ran`, `total` and every step or check. A client has no exit code, so read `passed`.
- **Images:** a failing step's picture is written to a file under `evidence`; the answer names it.

## What the endpoint does and does not do

- **Stateless.** Each POST carries one JSON-RPC message and gets its answer in the response. There is no MCP session to keep alive, and no server-sent stream (a GET answers 405).
- **Tools only.** The server offers tools. No prompts or resources.
- **Local only.** It listens on loopback and refuses requests from web pages (see [Security](/swing-ui-agent/agents/security/)). A client must run on the same machine as the app.
- **The CLI next to it.** Even with an MCP client set up, the agent jar's command line still works; the skills use it. See [CLI or MCP?](/swing-ui-agent/agents/cli-or-mcp/).

## When it does not connect

- **Nothing listens.** The app is not running with the agent, or it uses another port. `java -jar <agent jar> --port <port> session` exits with `3` in that case.
- **HTTP 403.** The request carried an `Origin` header for a host other than localhost, as a web page would. MCP clients normally send none.
- **The client wants SSE.** This server speaks streamable HTTP only, not the older HTTP+SSE transport. Pick the "http" or "streamable HTTP" transport in the client.

## What's next

- [Tools](/swing-ui-agent/reference/tools/): every tool and its arguments.
- [Skills](/swing-ui-agent/agents/skills/): they work for MCP clients too; read the CLI calls as tool calls.
