---
title: One order, two files
description: The same Order Desk order as a recorded test and as a Java test, what each can check, and where a technical check would go.
sidebar:
  order: 3
---

Order Desk has one scenario in two files. A new order for Ada Shop Ltd goes through checkout and is placed.

- **`uitest/place-order.json`** is a recorded test. A person can read it, replay it from the palette or the agent, and
  run it in Maven and CI. There is nothing to compile.
- **`PlaceOrderUiIT`** is a Java test: a JUnit 5 class with `@UiTest`.

Both were recorded by an agent and reviewed by a person. Both start at a reset and both end with the order placed.
They differ in what they may check at the end.

## What the recorded test checks

A recorded test holds exact values only: what a person could read off the screen when it was recorded. At the end of
`place-order`, the agent confirms the order and checks the order's tab that opens, then the Invoice tab:

<!-- snippet: uitest/place-order.json#L283-L323 -->
```json
    {
      "action" : "answer",
      "say" : "Confirm the order",
      "button" : "Place order",
      "checks" : [
        {
          "say" : "The new order is Placed",
          "target" : {
            "role" : "label",
            "name" : "Placed"
          },
          "text" : "Placed"
        },
        {
          "say" : "The receipt shows Ada Shop Ltd and €37.20",
          "target" : {
            "role" : "label",
            "name" : "Ada Shop Ltd · 12 Oct 2026 · €37.20"
          },
          "text" : "Ada Shop Ltd · 12 Oct 2026 · €37.20"
        }
      ]
    },
    {
      "action" : "click",
      "say" : "Open the Invoice tab",
      "target" : {
        "role" : "tab",
        "name" : "Invoice"
      },
      "checks" : [
        {
          "say" : "The payment line shows the PO number PO-7781",
          "target" : {
            "role" : "label",
            "name" : "Invoice (30 days) · PO PO-7781"
          },
          "text" : "Invoice (30 days) · PO PO-7781"
        }
      ]
    }
```

The receipt line holds a date, `12 Oct 2026`. Order Desk takes "today" from `-Dorderdesk.today=2026-10-12`, and the
README, the IntelliJ configuration and the CI script all start it that way. If you replay `place-order` by hand, start
the app the same way.

There is no order number in the file. The app gives each new order the next number, `ORD-1640` on a fresh start and a
different one after that, so the recorded test cannot know it. It checks what is predictable: **Placed**, the receipt
and the PO line. A recorded test never reads a value from the app to use later; it has no parameters or captures. That
is on purpose. It keeps the file readable by anyone who can use the app, and it is why the two kinds do not mix.

## What the Java test checks

`PlaceOrderUiIT` runs the same scenario and ends differently. It reads the new order's number from the status bar, then
looks for that order in the Orders table:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L76-L84 -->
```java
// the number is the app's next one: wait for the status bar to name the order whose tab is selected, so a
// rerun against a running app passes too (the last run's "Order … placed" shows until this one replaces it)
String order = eventually("the status bar naming the new order", Duration.ofMillis(8000L), () -> {
    String placed = ui.main().label("placed").contains().text();   // "Order ORD-1640 placed"
    String number = placed.substring("Order ".length(), placed.indexOf(" placed"));
    return ui.tab(number).states().contains("selected") ? number : null;
}, number -> number != null);
assertThat(ui.tab(order)).isSelected();
ui.inWindow("main").saveScreenshot("PlaceOrderUiIT/09-placed.png", 2);
```

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L86-L90 -->
```java
ui.tab("Orders").click();

assertThat(ui.tab("Orders").table().cell("Status")).hasCell(row("Order", order), "Status", "Placed");
assertThat(ui.tab("Orders").table().cell("Status")).hasCell(row("Order", order), "Total", "€37.20");
ui.inWindow("main").saveScreenshot("PlaceOrderUiIT/10-orders-after.png", 2);
```

That is Java: a variable (`order`), a wait for a value that changes, and a lookup with it. A recorded test cannot do
that. The Java test is also where a 2x screenshot is taken after every step.

## Where a technical check would go

Order Desk keeps its data in memory, so it has no database to look into. In a real app, "the order is placed" may also
mean a row in a database, a file on disk or a line in a log. The screen does not show those, and a recorded test cannot
read them. That check is Java: after the UI steps, the test reads the new order's number as above and asks the database
for it. The UI steps can come from a recording; the technical check is code that you or the agent write next to it.

## Which one to write

- Start with a recorded test. It covers what a person can see, it is cheap to fix, and it replays from the palette.
- Write a Java test when the test must read a value that changes per run, or check something the screen does not show.

[Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/) has the longer comparison.

## Run both

With Order Desk running with the agent, as in [Run the tests](/swing-ui-agent/start/run/):

- **The recorded test:** press **Load recorded test…** in the palette and **▶ Run**, or ask the agent to
  `run_recorded_test`.
- **Both in Maven:** `RecordedUiIT` replays every file in `uitest/`, and `PlaceOrderUiIT` runs next to it, in
  `mvn -B -Pui verify`.

## What's next

- [`PlaceOrderUiIT`, step by step](/swing-ui-agent/demo/recorded-test/): the Java test with its ten screenshots.
- [Run and edit a recorded test](/swing-ui-agent/start/recorded-tests/)
