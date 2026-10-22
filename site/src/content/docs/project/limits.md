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
- **Scrolling by `to` or `pages`** needs the element a scroll pane shows, such as the table itself; anything else is refused as "not inside a scroll pane". A `row` scrolls every pane around the table.
- **Popup windows:** open popup menus, combo dropdowns included, are the `popup` window. A suggestion list the app shows in another kind of popup window is not seen; one drawn inside the main window is.
- **Mouse input:** a component counts as taking mouse input when it has mouse listeners or a popup menu. One that only enables mouse events internally is seen as transparent, so a click on it lands on what is below.
- **No tool of its own:** `focusFileChooserName` is a step without a tool. Run it through `batch` or `do_action`. (`hover`, `resize_column`, `resize_window` and `close_window` are tools now.)

## Recording and export

- **Not exportable:** a `row` matched on more than two columns, a `loginAs` that is not the first step, and a `point` on anything but a plain left click (that one is exported as `clickAt`). The step runs and its result says `notRecorded`; pick another locator, or write that line by hand.
- **Screenshots in a recording:** a recorded `screenshot` step, and `saveScreenshot` in Java, always take the whole window: no region.
- **Starting screen:** without a plugin, a reset only closes modal dialogs, so the app may be on any screen. Each test reaches its own starting screen through the menu bar or a shortcut, and the exports warn when a recording starts any other way.

## Values from earlier steps

How links work is in [Values from earlier steps](/swing-ui-agent/guides/values-from-earlier-steps/). What they do not cover yet:

- **Not linked automatically:** values shorter than 4 characters, yes/no/true/false, whole numbers below 1000; a value in another form (`ORD 1640` for `ORD-1640`, another date format, a number inside a longer text); a row or item when the step added several. Short values can be linked by hand. A value in another form or from several added rows cannot: remember it from the screen, or write a Java test.
- **A check against its own element:** a check of the label, field or cell that the earlier step itself changed is never linked to that change of its own (it would compare the value with itself). When the same value also came as a new row, a field elsewhere or a remembered value, the check is linked to that. Only a value shown nowhere else stays exact and fails on the next run: remember it, or check something that is the same on every run.
- **Late changes:** while recording, a step keeps collecting what changes until the next step, however long that takes. Something that changes by itself, such as a clock, can join it. Such values rarely stand out enough to be linked. A replay waits for a late value right after its step, before the next one runs; a value that only comes after a later step is not that step's.
- **Folded steps:** a plugin's domain step folded from raw steps holds what they changed together, so a later step links to it. Two raw steps that each add a row to the same table give it two added rows, and neither is linked by position. A link breaks a fold: a step that is linked is not folded, and a fold that later steps use is not unfolded until they use exact values.
- **What is read:** text fields (also a spinner's), drop-downs, check boxes, radio buttons, toggles, labels, buttons, tables, lists, trees, tabs, window and dialog titles, and message dialogs. Not read: password fields, menus, and components the app paints itself, such as charts, canvases or a grid that is not a table. They give no values to link.
- **Large tables:** a reading has 40 ms. Past that, a table, list or tree is read only around what is in view and the selection (five rows on either side), and the outcome says `"partial": true`. A change outside that may be missing, and a value there is then not linked.
- **Caps:** at most 50 entries per added, removed or changed list (the `count` stays true), texts cut at 500 characters. While recording, the last 20 steps keep everything they changed; older ones keep only their new values.
- **Moved rows:** a row the app moves to another place, for example to the top, can look like an added one.
- **Plugin step values:** in a Java export, a `do` step whose Java form is a typed call or several steps cannot be the source of a later step's value. The recorded test runs it.

## The palette and recording by showing it

- **Small dialogs:** the palette follows an open modal dialog. One too small to hold it (a short confirmation) gets it in a small window of its own just under the dialog. That window never takes the keyboard from the dialog, so a step's sentence cannot be typed in the Steps list while the palette is there.
- **The agent's question bar:** it stays in the window of what the agent asks about, even when another window is in front.
- **Column checks:** checks on a whole table column are shown greyed, because no `expect` kind exists for them yet.
- **Not recorded as steps of their own yet:** clicks on spinner arrows, and drags. Re-do those with the agent, or write them by hand.
- **Lists and trees:** a click on a list entry or a tree row is recorded by its text, also a pick from a suggestion popup such as Order Desk's Customer field. Two entries that read the same cannot be told apart, and a tree's expand arrow has no step of its own, so those clicks are not recorded: the palette says so (✗ not recorded) and the agent finds them under `notRecorded`. To expand or collapse a row while recording, double-click it.
- **Popups that fill in a field:** a day picked in a calendar, or any value a popup writes into a text field, is recorded as one `type` of the field's new text when the popup closes, so the replay types it without opening the popup. The click that opened the popup is not a step, and closing it without a change records nothing. This covers popups opened from the field, from a button inside it, or from a button beside it when nothing else around holds a field. A popup that is a window of its own, not a Swing popup menu, is not covered: type the value instead.
- **A tab's close button** is painted by the look and feel and has no element of its own, so it can be neither found nor recorded. Close the tab as a user would with the menu or shortcut: in Order Desk File ▸ Close tab, or Mod+W (⌘W on macOS, Ctrl+W elsewhere).
- **Password fields:** never recorded, by design. What a person types into one is not stored, and the agent never reads one.
- **Replay all** begins with a reset, as the test will. Without a plugin that only closes dialogs, so there is no defined start to return to: the first step has to get there.
- **Windows and Linux:** the palette's automated tests run there, but it has not been tried by hand yet.

## What's next

- [Roadmap](/swing-ui-agent/project/roadmap/)
- [Security](/swing-ui-agent/agents/security/)
