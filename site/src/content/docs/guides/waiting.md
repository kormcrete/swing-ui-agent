---
title: Waiting
description: "Why there are no sleeps: every step waits for its target and for the app to settle."
sidebar:
  order: 3
---

A UI test usually fails because it went too fast: it clicked before the button was enabled, or checked before the table was filled. swing-ui-agent waits for you, so neither the agent nor a test needs a `sleep`. This page shows what every step waits for, how long, and what to do about work the driver cannot see.

The examples run against [Order Desk](/swing-ui-agent/demo/tour/) from its folder, with `UI="java -jar target/swing-ui-agent-agent.jar"`.

## What a step waits for

Every step that acts (`click`, `type`, `press`, `select`, `menu`, `pick`, `answer`, …) goes through the same three phases:

1. **Before acting**, it waits until its target matches exactly one element that can take the input: showing, enabled, not covered, not behind a modal dialog. Default: 10 seconds.
2. **It acts**: it posts the mouse or key events into the app's event queue, where Swing handles them as it would a user's.
3. **After acting**, it waits until the app has settled:
   - the event queue has drained, including everything the handlers queued in turn;
   - a modal dialog that appeared ends the wait at once (see [A dialog comes back as pending](#a-dialog-comes-back-as-pending));
   - no busy signal is on. Built in: a visible glass pane that takes mouse input, the usual "please wait" blocker. A [plugin](/swing-ui-agent/plugins/what-a-plugin-adds/) can register more, such as the app's own busy spinner.

   Default: 30 seconds; `menu`, `pick` and `context_menu` allow 120 seconds, since a menu item often starts a long job.

A `timeout` on the step (in milliseconds) replaces both defaults for that step:

```sh
$UI click '{"target":{"role":"button","name":"New order"},"timeout":1000}'
```

If the target never becomes actionable, the step fails with the reason (`NOT_FOUND`, `AMBIGUOUS` or `NOT_ACTIONABLE`). `AMBIGUOUS` comes sooner: when the same elements have matched for one second, waiting longer will not narrow them to one, so the step fails then rather than at its timeout. Make the target more specific (a `scope`, a `window` or `nth`, as the message suggests) instead of raising the timeout. If the action ran but the app did not settle in time, the step still passes, with a `warning` that names what stayed busy; a test prints it as an `[ui-warning]` line.

## Checks retry

`expect` does not act; it reads the element until the check holds, 10 seconds by default. A check against a value that is still on its way passes as soon as it arrives:

```sh
$UI expect '{"target":{"scope":["tab:New order · Ada Shop Ltd"],"role":"label","name":"Total"},"text":"€31.32"}'
```

In Java, every `assertThat(...)` from `UiAssertions` retries the same way. `within` sets the time for one check:

```java
assertThat(ui.table("Orders")).within(Duration.ofSeconds(30))
        .hasCell(row("Order", "ORD-1640"), "Status", "Placed");
```

`ui.timeout(Duration)` gives a client, and every target made from it, another default; `target.timeout(Duration)` does it for one element.

## A dialog comes back as pending

A step that opens a modal dialog does not hang on it. It returns at once with `pending`, which describes the dialog, and the next step answers it. See [Dialogs and file choosers](/swing-ui-agent/guides/dialogs/#a-step-that-opens-a-dialog).

## Errors stop the step

- **An uncaught exception** on the event dispatch thread while the step runs fails it as `APP_ERROR`, with the stack.
- **An error dialog** fails it as `APP_ERROR` too, when a plugin tells the driver which dialogs report errors. Without a plugin, an error dialog is a pending dialog like any other.

## Work the driver cannot see

The driver knows the event queue and its busy signals. It does not know about a background thread or a `SwingWorker` that is still running. Order Desk's Place order is one: the dialog shows "Placing order…" while a worker runs, and the step that started it is already done.

```sh
$UI answer '{"button":"Place order"}'
```

```json
{"passed":true,"action":"answer(Place order)","message":"","ms":14}
```

Wait for what the user waits for instead: the result on screen. The next check retries until the order is placed:

```sh
$UI expect '{"target":{"window":"main","role":"label","name":"placed","match":"contains"},"state":["!hidden"]}'
```

```json
{"passed":true,"action":"expect(label 'placed'~ in main state [!hidden])","message":"","ms":1780}
```

When a value has to be read and reasoned about, `UiAssertions.eventually` retries a read until a condition holds. Order Desk's `PlaceOrderUiIT` uses it to learn the new order's number:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L74-L83 -->
```java
ui.answer("Place order");

// the number is the app's next one: wait for the status bar to name the order whose tab is selected, so a
// rerun against a running app passes too (the last run's "Order … placed" shows until this one replaces it)
String order = eventually("the status bar naming the new order", Duration.ofMillis(8000L), () -> {
    String placed = ui.main().label("placed").contains().text();   // "Order ORD-1640 placed"
    String number = placed.substring("Order ".length(), placed.indexOf(" placed"));
    return ui.tab(number).states().contains("selected") ? number : null;
}, number -> number != null);
assertThat(ui.tab(order)).isSelected();
```

If your app has a busy indicator that every long job shows, register it in a plugin as a busy signal: then every step waits for it, and no test needs to know.

## What's next

- [Reset and the starting screen](/swing-ui-agent/guides/reset/): what happens before each test.
- [Dialogs and file choosers](/swing-ui-agent/guides/dialogs/): answering what a step left pending.
