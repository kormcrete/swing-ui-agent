---
title: CLI
description: The agent jar as a one-shot command line client for every tool.
sidebar:
  order: 2
---

The agent jar is also a command line client. Each run calls one tool on the running agent and exits.

```sh
java -jar swing-ui-agent.jar [--host 127.0.0.1] [--port 8765] [--out FILE] [--lease T] <tool> ['<json arguments>']
java -jar swing-ui-agent.jar [--port N] tools [<tool>]
```

| Option | What it does |
| --- | --- |
| `--host` | The agent's address. Default `127.0.0.1`. |
| `--port` | The agent's port. Default `8765`. |
| `--out FILE` | For a tool that returns an image (`screenshot`): write the PNG to `FILE`. |
| `--lease T` | Pass the token of a held lease (the `session` op `lease` returns one). |

`tools` lists every tool with its first sentence; `tools <tool>` prints that tool's full schema. The same schemas are on the [Tools](/swing-ui-agent/reference/tools/) page.

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | OK |
| `1` | The tool or the agent reported an error (on stderr) |
| `2` | Usage error |
| `3` | No agent reachable |
