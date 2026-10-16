---
title: Roadmap
description: What comes next.
sidebar:
  order: 2
---

No dates, just the order we expect to work in. Each item closes a gap from [Limits](/swing-ui-agent/project/limits/) or makes the project easier to adopt.

## Next

- **Publishing to Maven Central**, so the library and the agent jar no longer have to be built from source.
- **The site checked against Order Desk in CI.** Today the Order Desk examples on this site are copied from the demo repository, and a script compares them with it. Next, a deploy job builds the demo, starts it with the agent, runs its UI tests and refreshes the tool reference before every deploy.
- **Recording by showing it, filled in:** a check kind for whole table columns, spinner arrows, drags, and list and tree items.
- **Export gaps:** `point` and `item` targets in exported tests, and a tool of their own for `hover`, `resizeColumn`, `resizeWindow`, `closeWindow` and `focusFileChooserName`.

## Later

- **A recorded test as the base of a Java test:** an engineer generalises a recorded test into a Java test. Today the two kinds do not convert into each other; see [Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/).
- **For app users, not only developers:** someone with only the built app, started with the agent when needed, drives
  it in plain language with an agent, by typing or by voice, and keeps tests as scripts or plain-language steps with
  their checks, run by the agent with nothing to compile.
- **Linux and Windows on more real desktops:** GNOME and KDE, Wayland through XWayland, display scaling, and the palette tried by hand on both.
- **A plugin tutorial on Order Desk:** reset to a defined start, a login and a typed hook, built step by step.
- **Javadoc** of the library on this site.
- **A custom domain** for this site.
