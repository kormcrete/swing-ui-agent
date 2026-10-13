---
title: Record a test with the agent
description: Reset, reach the starting screen, act, check with expect, and export a JUnit class.
sidebar:
  order: 6
---

You say what the test must prove; the agent drives the app and every step that works is recorded. `export_test` writes the recording as a JUnit 5 `@UiTest` class, which you review like any other code. This page records a short test on Order Desk with the real answers, then reads the larger test that came out of the same process.

With the [skills](/swing-ui-agent/agents/skills/) installed, you ask in plain words, for example: "Record a test that opens a new order for Ada Shop Ltd and checks her account terms." The swing-ui-record-test skill takes the agent through the steps below.

## 1. Explore with reads

Before recording, the agent finds the route and every target with reads: `snapshot`, `read`, `screenshot`. Reads are never recorded.

## 2. Reset

```sh
$UI session '{"op":"reset"}'
```

```json
{"reset":"dialogs"}
```

A reset starts a fresh recording. Without a [plugin](/swing-ui-agent/plugins/what-a-plugin-adds/) it moves the pointer home and closes modal dialogs, nothing more: the app may still be on any screen.

## 3. Reach the starting screen

So the first step must get to the test's own starting screen from anywhere: a `menu` item, a key shortcut (`press`) or, with a plugin, `navigate`.

```sh
$UI menu '{"path":["File","New order"]}'
```

## 4. Act

```sh
$UI type '{"target":{"role":"text","name":"Customer","scope":["tab:New order"]},"text":"Ada"}'
$UI press '{"keys":"Enter"}'
```

Each answer carries `recorded`, the step as it will be exported:

```json
{"passed":true,"action":"type(text 'Customer' [tab:New order] 'Ada')","message":"","ms":18,"recorded":{"action":"type","target":{"scope":["tab:New order"],"role":"text","name":"Customer"},"text":"Ada"}}
```

A step that fails is not recorded. A step that ran but was wrong is: reset and redo from step 3 rather than patching it with more steps.

## 5. Check with `expect`

Checks are steps too, so they land in the test:

```sh
$UI expect '{"target":{"role":"tab","name":"New order · Ada Shop Ltd"},"state":["selected"]}'
$UI expect '{"target":{"role":"label","name":"Invoice 30 days","match":"contains","scope":["tab:New order · Ada Shop Ltd"]},"state":["!hidden"]}'
```

`expect` takes exactly one of `state`, `text`, `value`, `tooltip`, `rows`, `cell` or `selected`, and retries until it holds (10 seconds by default). The agent asserts what you asked for, not everything it can see.

## 6. Export

```sh
$UI export_test '{"class":"NewOrderForAdaUiIT","package":"io.github.kormcrete.orderdesk","method":"opensANewOrderForAda"}'
```

The answer names the file (`savedTo`, under `-Dswingui.testsDir`), the number of steps and how many were `fragile`, and holds the source:

```java
package io.github.kormcrete.orderdesk;

import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.junit.UiTest;
import org.junit.jupiter.api.Test;

import static io.github.kormcrete.swingui.junit.UiAssertions.assertThat;

/** Recorded through swing-ui-agent. Replace literals the test creates with ui.runId(). */
@UiTest
class NewOrderForAdaUiIT {

    @Test
    void opensANewOrderForAda(Client ui) {
        ui.menu("File", "New order");
        ui.tab("New order").field("Customer").type("Ada");
        ui.press("Enter");

        assertThat(ui.tab("New order · Ada Shop Ltd")).isSelected();
        assertThat(ui.tab("New order · Ada Shop Ltd").label("Invoice 30 days").contains()).isVisible();
    }
}
```

- `class` is required and ends in `UiIT`. `method` defaults to `recorded`, `package` to `recorded` (or the plugin's export package).
- An export clears the recording. Reset again before the next test.
- `export_test` refuses a recording that did not begin at a reset, an empty one, bad names and an existing file. A refusal keeps the recording, so fix the problem and export again.

### The start warning

A test that does not begin by reaching its own screen only works on whatever screen the app happens to show. The export still writes it, and says so:

```json
{"savedTo":"…/OpenOrdersUiIT.java","steps":1,"fragile":0,
 "startWarning":"the test begins with 'expect(label '4 open orders' state [!hidden])', which works only on the screen the app happens to show after a reset (no app plugin: a reset only closes dialogs); begin with a menu, a key shortcut or navigate so the test gets to its own starting screen"}
```

Reset and record again with a `menu` or `press` first step.

## 7. Prove it from another screen

Put the app on some other screen, such as another tab, then run only the new class while the app is up. It must pass from there. How to run it is in [Run the tests](/swing-ui-agent/start/run/).

## Reading a longer test

`PlaceOrderUiIT` in the Order Desk repository was recorded the same way: File ▸ New order, Ada Shop Ltd, two products in the cart, the four checkout steps and the placed order on the Orders tab. Its opening steps:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L17-L45 -->
```java
@UiTest
class PlaceOrderUiIT {

    @Test
    void placesAnOrderForAda(Client ui) {
        ui.menu("File", "New order");

        assertThat(ui.tab("New order")).isSelected();
        ui.inWindow("main").saveScreenshot("PlaceOrderUiIT/01-new-order.png", 2);

        ui.tab("New order").field("Customer").type("Ada");
        ui.press("Enter");

        assertThat(ui.tab("New order · Ada Shop Ltd")).isSelected();
        assertThat(ui.tab("New order · Ada Shop Ltd")
                .label("Account · Invoice 30 days · Credit limit €2,000.00").contains()).isVisible();
        ui.inWindow("main").saveScreenshot("PlaceOrderUiIT/02-customer.png", 2);

        ui.tab("New order · Ada Shop Ltd").table().row("Product", "A4 paper, 500 sheets").cell("SKU").click();
        ui.tab("New order · Ada Shop Ltd").button("Add to cart").click();
        ui.tab("New order · Ada Shop Ltd").table().row("Product", "Gel pen, black").cell("SKU").click();
        ui.tab("New order · Ada Shop Ltd").button("Add to cart").click();
        ui.tab("New order · Ada Shop Ltd").table().row("Product", "A4 paper, 500 sheets").cell("Qty")
                .type("3", null, Boolean.TRUE);
        ui.tab("New order · Ada Shop Ltd").table().row("Product", "Gel pen, black").cell("Qty")
                .type("10", null, Boolean.TRUE);

        assertThat(ui.tab("New order · Ada Shop Ltd").label("€26.70")).isVisible();
        assertThat(ui.tab("New order · Ada Shop Ltd").label("−€0.60")).isVisible();
```

What a reviewer looks for:

- **It begins at its own screen:** `ui.menu("File", "New order")` works from any tab.
- **Each check is something the test must prove:** the account terms, the cart totals, the order on the Orders tab.
- **Values the app makes up are not hard-coded.** The order number is the app's next one, so the review replaced the recorded `ORD-1640` with a read of the status bar. This is the one hand-written part:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L76-L83 -->
```java
// the number is the app's next one: wait for the status bar to name the order whose tab is selected, so a
// rerun against a running app passes too (the last run's "Order … placed" shows until this one replaces it)
String order = eventually("the status bar naming the new order", Duration.ofMillis(8000L), () -> {
    String placed = ui.main().label("placed").contains().text();   // "Order ORD-1640 placed"
    String number = placed.substring("Order ".length(), placed.indexOf(" placed"));
    return ui.tab(number).states().contains("selected") ? number : null;
}, number -> number != null);
assertThat(ui.tab(order)).isSelected();
```

Add by hand only checks no tool expresses, as here with `UiAssertions.eventually`. To change a recorded step, record it again. The whole test, step by step with its screenshots, is in [The recorded test](/swing-ui-agent/demo/recorded-test/).

## What's next

- [Run the tests](/swing-ui-agent/start/run/).
- [Record a test by showing it](/swing-ui-agent/start/show/): record the steps yourself, and let the agent export them.
- [Waiting](/swing-ui-agent/guides/waiting/) and [Reset and the starting screen](/swing-ui-agent/guides/reset/).
