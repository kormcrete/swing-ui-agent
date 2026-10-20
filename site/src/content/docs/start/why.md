---
title: Why swing-ui-agent
description: What swing-ui-agent is, what it is not, and what it needs.
sidebar:
  order: 1
---

swing-ui-agent lets you record UI tests of your real Swing app, and lets a coding agent do the work. A person shows the test with the palette in the app, or tells the agent in words while it drives the live app. The result is a recorded test: a JSON file with a sentence per step and nothing to compile, which replays from the palette, the agent, Maven or CI. You attach one jar to the app's JVM as a Java agent; your app's code does not change. When a test needs more or more technical checks, such as a database, files or logs, the agent writes a JUnit 5 test instead.

![Order Desk, the demo app, on its Orders tab](../../../assets/order-desk/01-orders-light.png)

## Who it is for

- Teams with a Swing desktop app that works and earns its keep, but has few or no UI tests.
- Teams that use a coding agent, or want to, and would rather review tests than write them.
- Apps on old Java: the agent is built for Java 8 and attaches to apps on Java 8 to 25.

## How it works

- **Drive:** the agent jar runs inside your app and serves a set of tools: `snapshot`, `click`, `type`, `menu`, `expect`, `screenshot` and more. A coding agent calls them from a shell (`java -jar <agent jar> <tool> '<json>'`) or as an MCP client. See [Let your agent drive the app](/swing-ui-agent/start/drive/).
- **Record:** every step that works is recorded. `export_recorded_test` saves the recording as a recorded test (a JSON file in `uitest/`, nothing to compile); `export_test` writes it as a JUnit 5 `@UiTest` class when a test needs more. See [Record a test with the agent](/swing-ui-agent/start/record/).
- **Show it yourself:** with the palette that is on by default, a person records the steps in the app and the agent exports them. See [Record a test by showing it](/swing-ui-agent/start/show/).
- **Run:** a recorded test replays against the running app from the palette, the agent, Maven, your IDE or CI; a Java test connects to the app the same way. See [Run the tests](/swing-ui-agent/start/run/).

The tests are meant to be written by the agent, not by hand: a recorded test it exports, or a Java test it writes from a recording. You review them, and you run them. A person who shows the app keeps the recording as a recorded test; an engineer keeps it as a Java test. See [Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/).

## The pieces

- **The agent jar** (`swing-ui-agent-<version>-agent.jar`): the Java agent for `-javaagent:`, and a command line client for the same tools.
- **The library** (`io.github.kormcrete:swing-ui-agent`): `@UiTest`, `Client` and the assertions, as a test dependency.
- **The skills** ([`skills/`](https://github.com/kormcrete/swing-ui-agent/tree/main/skills), [`agents/`](https://github.com/kormcrete/swing-ui-agent/tree/main/agents)): three agent skills that teach a coding agent to drive, target and record, and a driver sub-agent that does the clicking on a cheaper model. A Claude Code plugin from the kormcrete marketplace. See [Skills](/swing-ui-agent/agents/skills/).
- **A plugin** (optional): teaches the agent your app's own words, such as a reset to a defined state, a login or a screen by name. See [What a plugin adds](/swing-ui-agent/plugins/what-a-plugin-adds/).

## What it is not

- **Not for JavaFX or SWT.** Swing only.
- **No image matching.** It finds elements by what a user reads: labels, button text, column headers, cell text. Screenshots are for people.
- **It does not take over your mouse or keyboard.** Input is posted into the app's own event queue; it never uses `java.awt.Robot`. You can keep working while the agent drives the app, even with other windows in front.
- **Not a hosted service.** Everything runs on your machine or your CI runner, and the agent listens on the loopback address only.

## What it needs

- **Your app:** a Swing app on Java 8, 11, 17, 21 or 25, started with one extra `-javaagent:` flag. On Java 9 and newer it needs no `--add-opens` flags.
- **A desktop:** macOS, Linux (X11) or Windows. On a Linux machine without a display, a virtual one (Xvfb) works.
- **To build it:** JDK 8 and Maven 3. It is not on Maven Central yet.
- **For Maven and CI runs:** JUnit 5 (it runs recorded tests and Java tests alike). The palette and the agent replay a recorded test without it.

[Limits](/swing-ui-agent/project/limits/) lists what does not work yet.

## What's next

- [Installation](/swing-ui-agent/start/install/): build it and start an app with the agent.
- [Tour of Order Desk](/swing-ui-agent/demo/tour/): the demo app every example on this site uses.
