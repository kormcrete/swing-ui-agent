---
title: Screenshots
description: Images of the app for people, painted by the app itself.
sidebar:
  order: 9
---

A screenshot is for a person: to see what the agent sees, to review a layout, to keep a picture of each step of a test. It is not how a test checks anything; numbers and states are checks with `expect`. This page shows how screenshots are taken, from the command line, in a recorded step and in a test.

The examples run from the Order Desk folder, with `UI="java -jar target/swing-ui-agent-agent.jar"`.

## Painted, not grabbed

swing-ui-agent never captures the screen. It asks the app's own components to paint into an image. So:

- it works while the app is behind other windows or in the background, and it takes no focus;
- it shows only the app: no other application, no desktop, nothing private on the screen around it;
- anything in a window of its own (an open popup menu, a combo's dropdown, a tooltip) is not in the image of the window that opened it;
- the person's palette and the boxes the agent shows are left out;
- a component inside a scroll pane is painted as far as it shows: the part scrolled away is not in the image.

For a documentation picture of the window as the person sees it, `"palette":true` keeps the palette, the boxes and the menus open over the window in the image (default false):

```sh
$UI --out palette.png screenshot '{"window":"main","scale":2,"palette":true}'
```

## From the command line

`screenshot` returns the image. On the command line, `--out` writes it to a file and prints its size:

```sh
$UI --out orders.png screenshot '{"scale":2}'
```

```json
{"width":2400,"height":1500,"sourceWidth":1280,"sourceHeight":800,"scaled":true,"bytes":125679}
```

Without `--out`, the CLI prints a placeholder instead of the image bytes: `[image image/png, 49724 bytes; use --out FILE]`.

What to capture:

- **The current window**, by default: the focused one, a showing dialog included.
- **A whole window:** `"window":"main"`, `"dialog"`, `"dialog:<title>"` or `"window:<title>"`.
- **One element:** a `target`, such as a tab's content, a section, a table or a dialog: `{"target":{"role":"tab","name":"ORD-1640"}}`.
- **A part of it:** `region` with `x`, `y`, `w` and `h` as fractions of the target, `{"region":{"x":0,"y":0,"w":0.5,"h":0.2}}` for the top-left corner.

How big:

- **`scale`** (1 to 4, default 1) paints the app as a HiDPI display would: same layout, finer text and lines. Use 2 for images people will look at.
- **`maxSize`** is the longest side of the image; it is scaled down to fit. The default is 1200 times `scale`, so a 1280-pixel window at scale 2 comes out 2400 wide. Raise it for the full size:

```sh
$UI --out orders.png screenshot '{"scale":2,"maxSize":2560}'
```

```json
{"width":2560,"height":1600,"sourceWidth":1280,"sourceHeight":800,"scaled":false,"bytes":82671}
```

## In a test

A test saves screenshots through the `Client` API. A relative path lands under the test module's `target/ui-screenshots`. Order Desk's `PlaceOrderUiIT` keeps a 2x image after each step:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L50-L53 -->
```java
ui.tab("New order · Ada Shop Ltd").button("Checkout…").click();

assertThat(ui.dialog().field("Postal code")).hasValue("10119");
ui.inWindow("dialog").saveScreenshot("PlaceOrderUiIT/04-address.png", 2);
```

- `ui.inWindow("main")` or `ui.inWindow("dialog")` for a whole window, any target for one element: `ui.tab("Orders").table().saveScreenshot("orders.png", 2)`.
- `screenshot()` and `screenshot(scale)` return the PNG bytes instead of writing a file.

[PlaceOrderUiIT, step by step](/swing-ui-agent/demo/recorded-test/) shows the ten images Order Desk's CI run keeps.

## As a recorded step

The `screenshot` tool is a read and is never recorded. To have a screenshot in the test the agent exports, use the `screenshot` step in a `batch`, which writes a file and is recorded like any other step:

```sh
$UI batch '{"steps":[{"verb":"screenshot","window":"main","path":"orders/overview.png","scale":2}]}'
```

A relative `path` lands under `-Dswingui.screenshotDir` (set on the app), else `target/ui-screenshots` of the app's working directory. The exported test writes it with `saveScreenshot` as above.

## When a test fails

A failing `@UiTest` leaves a screenshot next to its `evidence.txt` under `target/ui-failures/`. See [Run the tests](/swing-ui-agent/start/run/#when-a-test-fails).

## What's next

- [Run in CI](/swing-ui-agent/start/ci/): keep the screenshots of a run as a build artifact.
- [Tools](/swing-ui-agent/reference/tools/): the `screenshot` tool's parameters.
