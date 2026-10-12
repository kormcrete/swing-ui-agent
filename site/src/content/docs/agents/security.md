---
title: Security
description: What the agent can and cannot do.
sidebar:
  order: 3
---

The agent listens on loopback only. No tool runs a command or evaluates code; `plugin_call` reaches only the methods a plugin declares. Use it on development and test machines, never against production data.

:::note[Being written]
This page is part of the first release of these docs. Until it is written, the [README](https://github.com/kormcrete/swing-ui-agent#readme) covers the basics.
:::
