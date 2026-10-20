---
title: Skills
description: Three agent skills and a driver sub-agent that teach a coding agent the workflow. In Claude Code they install as one plugin from the kormcrete marketplace.
sidebar:
  order: 2
---

A coding agent can find out on its own how to drive a Swing app, but it gets there faster and wastes fewer calls when
it is told. swing-ui-agent comes with three agent skills and a driver sub-agent that tell it. They are written for the
CLI; the same tools also work over MCP. In Claude Code they come as one plugin, `swing-ui-agent`, from the kormcrete
marketplace. Other coding agents copy the files.

## What you get

- **`swing-ui-record-test`** (skill): record a test and save it as a recorded test file or a Java `@UiTest` class.
  Which kind to pick is on [Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/). It covers:
  - the reset that starts a recording, and reaching the test's own starting screen first;
  - acting, then checking with `expect` so the check is recorded;
  - recording together with a person: the agent and the person share one recording and one undo history, and the
    agent only acts on what the person asked for ([how](/swing-ui-agent/start/record-together/));
  - fixing steps, naming them in sentences (a sentence per step, per check, and per group;
    [how](/swing-ui-agent/guides/sentences/)), and the exports and their warnings;
  - proving the test passes when the app starts on another screen;
  - working with a person at the app: asking with `inspect highlight` and `pick`, and saving what they recorded with
    the palette.
- **`swing-ui-targeting`** (skill): turn words into a locator, so "the Customer field", "order ORD-1640" or
  "Checkout…" becomes a target the agent can reach. It also says what to report when a word does not resolve, and
  what needs a plugin. `references/recipes.md` lists common jobs as live steps next to the Java each one exports to.
- **`swing-ui-driver`** (sub-agent, plus a skill of the same name that tells the main session when to use it): does
  the clicking for the main session. See below.

## The driver sub-agent

Driving an app is many small calls: open a screen, read it, click, check, read again. Each call and its answer stay in
the conversation. The driver takes that work into a sub-agent of its own, on a smaller and cheaper model (Sonnet). The
main session keeps the decisions and gets back only a short verdict.

**What it does.** It gets a checklist, each item with an outcome to see ("Checkout… stays disabled while a cart line
asks for more than is in stock"), plus any known targets, where to write results and what not to touch. It walks the
items with scoped reads and batched steps, and reports `PASS`, `FAIL` or `GAP` per item: a `FAIL` is the app's answer,
a `GAP` is something swing-ui-agent cannot do there. The details go into a results file; the main session reads a few
lines.

**When it kicks in.** Claude Code hands work to it on its own when the job is "go and look" or "go and do":

- walk a task through the app, or confirm a fix in the real app;
- reproduce a bug;
- run or re-run a recorded test or a Java UI test and report what failed;
- capture screens.

You can also ask for it: "Use the driver to walk the checkout for Café Lumen and tell me which steps fail."

**What stays in the main session.** Deciding what a test should prove, and recording a test you shape step by step
with the agent (`swing-ui-record-test`). The driver does not start, stop or restart the app, export a test or fix
code unless the hand-over says so, and it never works around a failure or a gap.

## Install in Claude Code

The plugin comes from the **kormcrete marketplace**, a GitHub repository that holds released versions of kormcrete's
Claude Code plugins: [github.com/kormcrete/claude-plugins](https://github.com/kormcrete/claude-plugins). In Claude
Code the marketplace is called `kormcrete`, so the plugin is `swing-ui-agent@kormcrete`.

**For yourself**, in Claude Code:

```text
/plugin install swing-ui-agent --marketplace kormcrete/claude-plugins
```

Before Claude Code 2.1.275 that takes two commands: `/plugin marketplace add kormcrete/claude-plugins`, then
`/plugin install swing-ui-agent@kormcrete`.

**For everyone on a project**, commit this to the project's `.claude/settings.json`. Claude Code offers to install the
plugin when someone opens the project and trusts the folder:

```json
{
  "extraKnownMarketplaces": {
    "kormcrete": {
      "source": { "source": "github", "repo": "kormcrete/claude-plugins" }
    }
  },
  "enabledPlugins": { "swing-ui-agent@kormcrete": true }
}
```

`/plugin` lists what is installed, with its version. The skills are named `swing-ui-agent:swing-ui-record-test`,
`swing-ui-agent:swing-ui-targeting` and `swing-ui-agent:swing-ui-driver`, and the sub-agent
`swing-ui-agent:swing-ui-driver`. Claude Code picks them by what you ask; you can also call a skill by name, for
example `/swing-ui-agent:swing-ui-record-test`.

**Tell it about your app.** The skills bring the workflow; your project's instructions (`CLAUDE.md`) bring your app's
facts: where the agent jar is and which port the app listens on (the skills show
`swing-ui-agent-0.1.0-SNAPSHOT-agent.jar` on 8765), how your tests run, and which kind of test you want by default.
[Record and run a UI test with Claude Code](/swing-ui-agent/start/claude-code/) shows such a section.

## Update

The plugin's version is the agent jar's: plugin 0.1.0 is written for agent jar 0.1.0. Until a version is released,
previews of it are numbered after it, `0.1.0-SNAPSHOT.1`, `.2` and so on, for agent jars built from that snapshot. The
marketplace holds published versions only, so nothing half-done reaches you. Update the plugin when you update the
agent jar, so the skills never describe tools your jar does not have:

```text
/plugin marketplace update kormcrete
/plugin update swing-ui-agent@kormcrete
```

Then restart Claude Code. To have Claude Code look for updates when it starts, add `"autoUpdate": true` next to
`"source"` in the marketplace entry above. To try the skills from a swing-ui-agent checkout instead, start Claude Code
with `claude --plugin-dir <checkout>`: for that session it uses the checkout instead of the installed plugin.

## Other coding agents

The files are in the swing-ui-agent repository:
[`skills/`](https://github.com/kormcrete/swing-ui-agent/tree/main/skills) and
[`agents/`](https://github.com/kormcrete/swing-ui-agent/tree/main/agents).

1. Copy the three skill folders into your agent's skills directory:

   ```sh
   cp -r swing-ui-agent/skills/swing-ui-* my-app/<your agent's skills folder>/
   ```

2. For the driver, copy `agents/swing-ui-driver.md` into your agent's sub-agent folder and adapt its frontmatter (a
   smaller model, the shell tool).
3. If your agent reads no skill folders, put the text of `swing-ui-record-test` and `swing-ui-targeting` into its
   instruction file.

Copied files do not update: copy them again after a release. As in Claude Code, say once in your project's
instructions where the agent jar is and which port the app listens on.

## Use them

Ask in your own words. For example:

- "Record a UI test that places an order for Ada Shop Ltd with 3 × A4 paper and checks the total."
- "Check by hand that Checkout… stays disabled when the cart has more Staplers than are in stock."
- "Use the driver to walk the checkout for Café Lumen and tell me which steps fail."

With the palette on (the default), you can also show the agent instead of describing it: see
[Record a test by showing it](/swing-ui-agent/start/show/).

## The docs in one file

The whole site is also plain text for agents: [`llms.txt`](https://kormcrete.github.io/swing-ui-agent/llms.txt) lists
the pages, [`llms-full.txt`](https://kormcrete.github.io/swing-ui-agent/llms-full.txt) holds all of them, and
[`llms-small.txt`](https://kormcrete.github.io/swing-ui-agent/llms-small.txt) a shorter version. Point your agent at
one of them when it should know the tool beyond what the skills teach.

## What's next

- [Let your agent drive the app](/swing-ui-agent/start/drive/)
- [Record a test with the agent](/swing-ui-agent/start/record/)
- [CLI or MCP?](/swing-ui-agent/agents/cli-or-mcp/)
