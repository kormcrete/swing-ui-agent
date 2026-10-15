---
title: Skills
description: Three agent skills that teach a coding agent the workflow.
sidebar:
  order: 2
---

A coding agent can find out on its own how to drive a Swing app, but it gets there faster and wastes fewer calls when it is told. The repository's [`skills/`](https://github.com/kormcrete/swing-ui-agent/tree/main/skills) folder holds three agent skills. Each is a folder with a `SKILL.md`, written for the CLI. The same tools also work over MCP.

## The three skills

- **`swing-ui-record-test`:** record a test and save it as a recorded test file or a Java `@UiTest` class. It covers:
  - the reset that starts a recording, and reaching the test's own starting screen first;
  - acting, then checking with `expect` so the check is recorded;
  - fixing steps, naming them in sentences, and the exports and their warnings;
  - proving the test passes when the app starts on another screen;
  - working with a person at the app: asking with `inspect highlight` and `pick`, and saving what they recorded with the palette.
- **`swing-ui-targeting`:** turn words into a locator, so "the Customer field", "order ORD-1640" or "Checkout…" becomes a target the driver can reach. It also says what to report when a word does not resolve, and what needs a plugin. `references/recipes.md` lists common jobs as live steps next to the Java each one exports to.
- **`swing-ui-driver`:** hand the clicking to a cheaper sub-agent. The main session keeps the decisions; the driver walks a checklist with scoped reads and batched steps, and reports pass, fail or gap per item. The file doubles as the sub-agent's definition.

## Install them

1. Copy the three folders into your agent's skills directory. For Claude Code, that is `.claude/skills/` in your project (or `~/.claude/skills/` for every project):

   ```sh
   cp -r swing-ui-agent/skills/swing-ui-* my-app/.claude/skills/
   ```

2. For the driver as a sub-agent, also make it an agent definition: copy `swing-ui-driver/SKILL.md` into your agent's sub-agent folder (for Claude Code, `.claude/agents/swing-ui-driver.md`). Add your platform's frontmatter, for example a smaller model and the shell tool.
3. If your agent reads no skill folders, put the text of `swing-ui-record-test` and `swing-ui-targeting` into its instruction file.

The skills show the agent jar as `swing-ui-agent-0.1.0-SNAPSHOT-agent.jar` on port 8765. If yours lives elsewhere or listens on another port, say so once in your project's instructions.

## Use them

Ask in your own words. For example:

- "Record a UI test that places an order for Ada Shop Ltd with 3 × A4 paper and checks the total."
- "Check by hand that Checkout… stays disabled when the cart has more Staplers than are in stock."
- "Use the driver to walk the checkout for Café Lumen and tell me which steps fail."

With the palette on (the default), you can also show the agent instead of describing it: see [Record a test by showing it](/swing-ui-agent/start/show/).

## What's next

- [Let your agent drive the app](/swing-ui-agent/start/drive/)
- [Record a test with the agent](/swing-ui-agent/start/record/)
- [CLI or MCP?](/swing-ui-agent/agents/cli-or-mcp/)
