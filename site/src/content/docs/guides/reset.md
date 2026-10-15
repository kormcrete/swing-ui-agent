---
title: Reset and the starting screen
description: How each test gets the app to where it begins.
sidebar:
  order: 3
---

UI tests run against an app that is already running, and that keeps running between tests. So a test cannot assume a fresh app: the last test may have left a tab open, an order half entered, or a dialog showing. This page shows what a reset does before each test, why every test begins by getting to its own starting screen, and how recording follows the same rule.

## What a reset does

Before each `@UiTest` method, and whenever the agent calls `session` with `{"op":"reset"}`, the agent:

1. moves the pointer away from the app, so nothing stays hovered;
2. closes every modal dialog with Escape, up to five times, then with a window close. A dialog that still shows fails the reset with its title;
3. with an app [plugin](/swing-ui-agent/plugins/what-a-plugin-adds/), runs the plugin's reset: close tabs, log in as the test's user, whatever brings that app to a defined state.

Without a plugin, steps 1 and 2 are all. The answer says so:

```sh
java -jar target/swing-ui-agent-agent.jar session '{"op":"reset"}'
```

```json
{"reset":"dialogs"}
```

The app is now free of dialogs, but it may be on any screen: the Orders tab, a half-filled New order tab, an order's receipt.

## Begin with a way in

So every test begins by getting to its own starting screen, the way a user would, from wherever the app is:

- a menu item: `menu {"path":["File","New order"]}`, in Java `ui.menu("File", "New order")`;
- a key shortcut: `press {"keys":"Mod+N"}`, in Java `ui.press("Mod+N")` (`Mod` is ⌘ on macOS and Ctrl elsewhere);
- with a plugin, a named screen: `ui.navigate("orders")`.

Order Desk's `PlaceOrderUiIT` opens a New order tab from the File menu, so it starts from the same place every time:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L22-L30 -->
```java
ui.menu("File", "New order");

assertThat(ui.tab("New order")).isSelected();
ui.inWindow("main").saveScreenshot("PlaceOrderUiIT/01-new-order.png", 2);

ui.tab("New order").field("Customer").type("Ada");
ui.press("Enter");

assertThat(ui.tab("New order · Ada Shop Ltd")).isSelected();
```

A test that begins with a click on whatever happened to be showing passes once and fails on the next run.

## Recording starts at a reset

The agent records the steps it runs, and `export_test` writes them as a test. The recording starts at a reset, because that is where the test will start:

1. `session {"op":"reset"}` closes dialogs and starts a fresh recording. `recording {"op":"start","reset":true}` does the same, and the palette shows the recording.
2. The first step gets to the starting screen (a menu, a shortcut, or `navigate`).
3. The steps and checks follow.
4. `export_recorded_test` writes a recorded test file, or `export_test` the Java class.

Both refuse a recording that did not begin at a reset:

```text
the recording did not begin at a session reset or a recording start, where the test will start: reset (session op reset), get to the starting screen, redo the steps, then export; or replay it from step 1 (recording replay) and export once that passes
```

Without a plugin, it also adds a `startWarning` when the first step is not a menu, a key press or `navigate`:

```text
the test begins with 'click(tab 'Orders')', which works only on the screen the app happens to show after a reset (no app plugin: a reset only closes dialogs); begin with a menu, a key shortcut or navigate so the test gets to its own starting screen
```

With a plugin that resets the app to a defined state, there is no warning: the test starts where the recording did.

`export_test` with `"clear":true` empties the recording after writing it; the next test needs a reset first.

## Write tests that pass on a used app

A reset does not undo what earlier tests did to the app's data. In Order Desk each placed order takes the next number and adds to what the customer owes. A test that passes twice:

- reads values the app made instead of expecting fixed ones: `PlaceOrderUiIT` reads the new order's number from the status bar (see [Waiting](/swing-ui-agent/guides/waiting/#work-the-driver-cannot-see));
- checks its own row, found by values, rather than the first row or a row count;
- leaves what it opened to the next reset, or closes it the way a user would.

To prove it, run the test twice, starting from a different screen each time.

## What's next

- [Tables](/swing-ui-agent/guides/tables/): finding rows by their values.
- [What a plugin adds](/swing-ui-agent/plugins/what-a-plugin-adds/): a reset to a defined state, login and navigation.
