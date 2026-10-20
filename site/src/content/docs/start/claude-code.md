---
title: Record and run a UI test with Claude Code
description: Let Claude Code drive Order Desk, write a UI test from your words or from what you show it, and run the tests.
sidebar:
  order: 5
---

With Claude Code, a coding agent drives the app and writes the test. You describe the test, or show it with the
palette, and the agent exports it as a recorded test or a JUnit class, runs it from another screen and fixes what it
flags. This works the same in the Claude Code terminal, the desktop app and the IntelliJ plugin: the agent calls the
agent jar's command line in each of them.

You will learn how to:

- give Claude Code the skills and tell it where the app listens;
- have it record a test from a description;
- record a test together with it, or yourself, and let it export and check it;
- have it run the tests.

## Before you start

- Order Desk set up as in [Record and run a UI test in IntelliJ](/swing-ui-agent/start/intellij/): swing-ui-agent
  installed, the project cloned and built.
- Claude Code in the Order Desk folder.

## 1. Give Claude Code the skills

Install the swing-ui-agent plugin, which brings the three skills and the driver sub-agent:

```text
/plugin install swing-ui-agent --marketplace kormcrete/claude-plugins
```

Then tell it once, in the project's `CLAUDE.md`, how to reach the app:

```markdown
## UI tests
- Order Desk runs with swing-ui-agent on port 8770 (run configuration "Order Desk", with the palette). Drive it with `java -jar target/swing-ui-agent-agent.jar --port 8770 <tool> '<json>'`.
- Save UI tests as recorded tests in `uitest/` (export_recorded_test) and replay them with run_recorded_test; `mvn -B -Pui verify` runs them all. Java tests (package io.github.kormcrete.orderdesk) only when I ask for one.
```

[Skills](/swing-ui-agent/agents/skills/) says what each skill covers, how to set the plugin up for a whole team, and
[what else your project can tell it](/swing-ui-agent/agents/skills/#your-projects-part).

## 2. Let it record a test from your words

Start **Order Desk** (from IntelliJ, or ask Claude Code to start it), then ask:

> Record a UI test that cancels order ORD-1638 from the Orders tab and checks that its status becomes Cancelled.

The agent:

1. resets the recording and gets the app to the starting screen through a menu item or the Orders tab;
2. does each step and reads the answer, then adds checks with `expect`;
3. saves it as the recorded test `uitest/cancel-order.json` with `export_recorded_test`;
4. puts the app on another screen and replays it with `run_recorded_test`, to prove it gets to its own start;
5. fixes what the export or the run flagged, such as a locator that needed "the 2nd table", and shows you the steps.

You review the file like any other change and commit it. When a test needs more than a recorded test can say, ask
for a Java test instead: the agent exports the recording with `export_test` as a JUnit class.

## 3. Record it together

Start **Order Desk** (the palette is on by default), then tell Claude Code what to do and watch:

> Record a test: open a new order for Ada, add 3 packs of A4 paper, check the cart total.

The agent calls `recording {"op":"start","reset":true}`, does the steps and the checks, and calls
`recording {"op":"stop"}`. Each step shows in the palette's Steps list at once, marked as the agent's. You can delete
a step, redo from it or undo, and ask the agent to change or name steps: you both edit one list. When it is right,
the agent saves it with `export_recorded_test` (or `export_test` for a Java class) and runs it with
`run_recorded_test`. See [Record together with your agent](/swing-ui-agent/start/record-together/).

## 4. Record it yourself and let it export

Or you do the steps. Tell Claude Code:

> I'll record a test for cancelling an order in the app. Wait for me, then save it as cancel-order and check it.

The agent waits for your recording. In the app:

1. press **● Record** and start with a step that gets to the starting screen;
2. use the app, add checks with **Check**, and fix the steps with **Steps**;
3. press **Done** when the steps are right.

Done hands the recording to the agent: it saves `uitest/cancel-order.json`, replays it from another screen and
reports back. Ask for a Java test (`CancelOrderUiIT`) instead when you need one.

If you would rather name and write the file yourself, press **Export…** instead and ask the agent to check the test.

While it works, the agent can ask you about the app in the app: it boxes what it found, numbered when there are
several, and you click the ✓ on the one you mean. See
[Record a test by showing it](/swing-ui-agent/start/show/#answer-the-agent-in-the-app).

## 5. Let it run the tests

> Run the UI tests and tell me what failed.

For a recorded test the agent calls `run_recorded_test {"name":…}`. For a Java test it runs `mvn -B -Pui verify`
against the running app and reads the results. For a failure it reads the evidence in `target/ui-failures/` (what the
step expected, the element tree, a screenshot) and can drive the app to see for itself. To have each run write a
one-line-per-test summary the agent can read, register the run report: see
[A run report for scripts](/swing-ui-agent/start/run/#a-run-report-for-scripts).

## Over MCP instead

Claude Code can also reach the agent as an MCP server: the same tools, without a shell call per step. See
[MCP clients](/swing-ui-agent/agents/mcp-clients/) and [CLI or MCP?](/swing-ui-agent/agents/cli-or-mcp/).

## What's next

- [Let your agent drive the app](/swing-ui-agent/start/drive/): what the agent's calls and answers look like.
- [Record together with your agent](/swing-ui-agent/start/record-together/) and
  [Record a test with the agent](/swing-ui-agent/start/record/): the recording rules in detail.
- [Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/).
- [Run in CI](/swing-ui-agent/start/ci/).
