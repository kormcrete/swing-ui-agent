---
title: Why swing-ui-agent
description: What swing-ui-agent is, what it is not, and what it needs.
sidebar:
  order: 1
---

swing-ui-agent is a Java agent you attach to your Swing app's JVM. A coding agent then drives the real app from a shell or over MCP, and what it did becomes a JUnit 5 test. It works on Java 8 and newer, needs no change to the app, and never uses `java.awt.Robot`, so it does not take over your mouse or keyboard.

:::note[Being written]
This page is part of the first release of these docs. Until it is written, the [README](https://github.com/kormcrete/swing-ui-agent#readme) covers the basics.
:::
