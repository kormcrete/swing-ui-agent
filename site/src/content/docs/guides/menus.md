---
title: Menus and popups
description: The menu bar, key shortcuts, dropdown buttons and context menus.
sidebar:
  order: 6
---

Menus are how a user starts most things in a desktop app, and how a test gets to its starting screen. swing-ui-agent has one step for each kind: `menu` for the menu bar, `press` for shortcuts, `pick` for the popup a button opens, and `context_menu` for a right-click. This page walks through each on Order Desk.

The examples run from the Order Desk folder, with `UI="java -jar target/swing-ui-agent-agent.jar"`.

## The menu bar

`menu` invokes an item by its label path, top menu first. Submenus are part of the path:

```sh
$UI menu '{"path":["File","New order"]}'
$UI menu '{"path":["View","Theme","Dark"]}'
```

```json
{"passed":true,"action":"menu([View, Theme, Dark])","message":"","ms":371,"recorded":{"action":"menu","path":["View","Theme","Dark"]}}
```

In Java: `ui.menu("File", "New order")` and `ui.menu("View", "Theme", "Dark")`.

- **A menu bar inside the window** is clicked through, menu by menu, as a user would.
- **The macOS screen menu bar** (`apple.laf.useScreenMenuBar`) has no input path the driver can post to. There, `menu` invokes the item directly, and the recorded step says `"via":"api"`.
- **A disabled item** fails the step, and so does a disabled menu or submenu on the way:

```json
{"passed":false,"action":"menu([File, Close tab])","reason":"NOT_ACTIONABLE","message":"NOT_ACTIONABLE: disabled: menu item 'Close tab'"}
```

To see a menu without opening it, read its items:

```sh
$UI read '{"form":"menuItems","path":["View","Theme"]}'
```

```json
{"found":true,"items":["Light","Dark"]}
```

In Java: `ui.menuItems("View", "Theme")`.

## Key shortcuts

`press` sends a key chord: a key with its modifiers, joined by `+`. `Mod` is the platform's menu-shortcut key, ⌘ on macOS and Ctrl elsewhere, so one test runs on every platform:

```sh
$UI press '{"keys":"Mod+N"}'
$UI press '{"keys":"Mod+W"}'
```

- Key names are `KeyEvent` names without `VK_`: `Enter`, `Escape`, `Tab`, `F5`, `Delete`.
- Modifiers: `Shift`, `Ctrl`, `Alt`, `Meta`, `Mod`.
- Without a target, the keys go to the showing modal dialog, else to the focused window. With a `target`, that element gets the focus first.

In Java: `ui.press("Mod+W")`, or `target.press("Enter")` for one element.

## Dropdown buttons

A button that opens a menu takes two steps: `click` the button, then `pick` down the path in the popup it opened. Order Desk's ▾ button, named by its tooltip, lists recent customers:

```sh
$UI click '{"target":{"role":"button","name":"New order for a recent customer"}}'
$UI snapshot '{"window":"popup","depth":3}'
```

```json
{"role":"app","children":[{"ref":"e157","role":"menu","name":"","children":[
  {"ref":"e158","role":"menuitem","name":"Ada Shop Ltd"},
  {"ref":"e159","role":"menuitem","name":"Café Lumen"},
  {"ref":"e160","role":"menuitem","name":"Birch & Co"},
  {"ref":"e161","role":"menuitem","name":"Fjord Supplies"}]}]}
```

```sh
$UI pick '{"path":["Birch & Co"]}'
```

In Java:

```java
ui.button("New order for a recent customer").click();
ui.pick("Birch & Co");
```

`pick` opens submenus on the way when the path has more than one label.

## Context menus

`context_menu` right-clicks a target and clicks down the path in the menu that opens. On a table, the target is a row:

```sh
$UI context_menu '{"target":{"scope":["tab:Orders"],"role":"table","row":{"Order":"ORD-1636"}},"path":["Copy order number"]}'
$UI read_clipboard
```

A context menu usually acts on the selection, so the row is selected first, as a right-click does in most apps, and
the menu's row items apply to it. A row that is already part of the selection keeps the whole selection, for an item
that acts on several rows. A list item and a tree row work the same way. The recorded step keeps the row, so the test
replays on the same one.

```json
{"text":"ORD-1636"}
```

In Java: `ui.tab("Orders").table().row("Order", "ORD-1636").rightClick("Copy order number")`, and `ui.clipboardText()` for the check.

To see what a context menu offers before choosing, right-click with `click` and `"button":"right"`, then take a snapshot of the `popup` window:

```sh
$UI click '{"target":{"scope":["tab:Orders"],"role":"table","row":{"Order":"ORD-1636"},"column":"Customer"},"button":"right"}'
$UI snapshot '{"window":"popup","depth":3}'
```

```json
{"role":"app","children":[{"ref":"e46","role":"menu","name":"","children":[
  {"ref":"e47","role":"menuitem","name":"Open"},
  {"ref":"e48","role":"menuitem","name":"Cancel orders…","state":["disabled"]},
  {"ref":"e49","role":"menuitem","name":"Copy order number"}]}]}
```

`press {"keys":"Escape"}` closes it again. A menu item that opens a dialog (Cancel orders… on a placed order) returns with the dialog `pending`; see [Dialogs and file choosers](/swing-ui-agent/guides/dialogs/).

## Popups and screenshots

A popup menu, a combo's dropdown and a tooltip each live in a window of their own. A `screenshot` of the main window does not show them; take the `popup` window's snapshot to read them. See [Screenshots](/swing-ui-agent/guides/screenshots/).

## What's next

- [Dialogs and file choosers](/swing-ui-agent/guides/dialogs/): what a menu item often opens.
- [Reset and the starting screen](/swing-ui-agent/guides/reset/): why tests begin with a menu or a shortcut.
