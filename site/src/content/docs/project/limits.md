---
title: Limits
description: What works today, and what does not yet.
sidebar:
  order: 1
---

swing-ui-agent is early (`0.1.0-SNAPSHOT`). This page lists what it covers today and the known gaps. A gap shows up as a clear error, usually `CAPABILITY_GAP` or a `notRecorded` note on a step, never as a step that silently did something else.

## Platforms and Java

- **Operating systems:** macOS, Linux (X11) and Windows, each tested in CI on every change (Linux under a virtual display). Other systems fail with a `CAPABILITY_GAP` error.
- **Real desktops:** Linux and Windows are tested on GitHub's runners and in virtual machines so far, not yet on many real desktops: GNOME or KDE, Wayland through XWayland, display scaling. Reports from yours are welcome.
- **In front or behind:** on all three the app can be driven while other windows are in front. On macOS, `mode=background` also keeps it out of the Dock.
- **Java:** one jar, built for Java 8 so it also attaches to legacy apps still on it. CI tests Java 8, 11, 17, 21 and 25 on macOS, and Java 8 and 25 on Linux and Windows. On Java 9 and newer the app needs no `--add-opens` flags.
- **Toolkits:** Swing only. No JavaFX, no SWT.
- **Distribution:** not on Maven Central yet. Build and install it from source.

## Driving

- **Screenshots** paint the app's own components into an image. Anything in a window of its own, such as an open popup menu, a combo dropdown or a tooltip, is not in a window's image; capture that window instead.
- **Drag and drop:** `drag` posts a mouse gesture inside one window and never starts a native drag. To deliver files or text, use `drop`.
- **The macOS screen menu bar** has no input path, so `menu` invokes its item directly. The step is marked `via:api`.
- **Mouse input:** a component counts as taking mouse input when it has mouse listeners or a popup menu. One that only enables mouse events internally is seen as transparent, so a click on it lands on what is below.
- **No tool of their own:** `hover`, `resizeColumn`, `resizeWindow`, `closeWindow` and `focusFileChooserName` are steps without a tool. Run them through `batch` or `do_action`.

## Recording and export

- **Not exportable:** a target addressed by `point` or `item`, a `row` matched on more than two columns, and a `loginAs` that is not the first step. The step runs and its result says `notRecorded`; pick another locator, or write that line by hand.
- **Starting screen:** without a plugin, a reset only closes modal dialogs, so the app may be on any screen. Each test reaches its own starting screen through the menu bar or a shortcut, and `export_test` warns when a recording starts any other way.

## The palette and recording by showing it

- **Narrow dialogs:** the palette follows an open modal dialog, but not into one narrower than its buttons. Then it stays in the main window and cannot be used until the dialog closes.
- **The agent's question bar:** it stays in the window of what the agent asks about, even when another window is in front.
- **Column checks:** checks on a whole table column are shown greyed, because no `expect` kind exists for them yet.
- **Not recorded as steps of their own yet:** clicks on spinner arrows, drags, and clicks on list or tree items. Re-do those with the agent, or write them by hand.
- **Password fields:** what a person types into one is never recorded.
- **Replay all** replays from the screen the app is on now. Without a plugin there is no defined start to return to first.
- **Windows and Linux:** the palette's automated tests run there, but it has not been tried by hand yet.

## What's next

- [Roadmap](/swing-ui-agent/project/roadmap/)
- [Security](/swing-ui-agent/agents/security/)
