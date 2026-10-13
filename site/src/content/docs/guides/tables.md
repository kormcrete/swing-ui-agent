---
title: Tables
description: Read a table, find rows by their values, click, edit and check cells.
sidebar:
  order: 4
---

Tables carry most of the data in a business app, and most of what a test checks. swing-ui-agent reads a table the way it is painted: rows in the order on screen, each cell as its renderer shows it. This page uses Order Desk's Orders table, its products table and its cart.

The examples run from the Order Desk folder, with `UI="java -jar target/swing-ui-agent-agent.jar"`.

## Read a table

`read` returns the columns and the rows as shown, in view order (after sorting and filtering). `maxRows` limits the rows:

```sh
$UI read '{"target":{"scope":["tab:Orders"],"role":"table"},"maxRows":3}'
```

```json
{"found":true,"node":{"ref":"e16","role":"table","state":["atTop","atBottom"],
 "columns":["Order","Date","Customer","Items","Total","Status"],
 "rows":[["ORD-1639","9 Oct 2026","Ada Shop Ltd","55","€95.52","Placed"],
         ["ORD-1638","7 Oct 2026","Café Lumen","8","€181.44","Placed"],
         ["ORD-1637","5 Oct 2026","Birch & Co","7","€141.36","Placed"]],
 "rowCount":12,"selected":[],"truncated":true,
 "scroll":{"x":0,"y":0,"width":1254,"height":566,"maxX":0,"maxY":0}}}
```

Each cell is its renderer's text: `€95.52`, not the number behind it. A check box cell reads as its state. A renderer that builds a small panel reads as the text of its parts.

## Which table

- **By its name:** a table's caption, a label linked to it or a titled border, `{"role":"table","name":"Orders"}`. In Java, `ui.table("Orders")`.
- **By its tab:** `{"scope":["tab:Orders"],"role":"table"}` when the tab holds one table. In Java, `ui.tab("Orders").table()`.
- **By a column only it has:** a `column` without a `row` narrows to the table with that header. Order Desk's New order tab has two unnamed tables, the products and the cart; only the cart has a Qty column:

```sh
$UI read '{"target":{"scope":["tab:New order · Ada Shop Ltd"],"role":"table","column":"Qty"}}'
```

```json
{"found":true,"node":{"role":"table","columns":["Product","Qty","Price","Line total"],
 "rows":[["A4 paper, 500 sheets","3","€4.90","€14.70"],["Gel pen, black","10","€1.20","€12.00"]],"rowCount":2,"selected":[1]}}
```

In Java, `ui.tab("New order · Ada Shop Ltd").table().cell("Qty")`.

## Rows and cells

- **A row by its values:** `"row":{"Order":"ORD-1640"}`, one or two columns. It finds the row wherever sorting put it. In Java, `.row("Order", "ORD-1640")`, or `.row(Rows.row("Customer", "Ada Shop Ltd", "Status", "Placed"))` for two.
- **A row by position:** `"row":{"index":0}`, the first row on screen whatever its data. In Java, `.rowAt(0)`.
- **A cell:** a row plus a `column`. In Java, `.row(...).cell("Status")`.
- **A header:** a `column` without a row, as a target to click.

A row named by more than two columns runs, but `export_test` has no Java form for it.

## Click, open and sort

Click a cell to select its row. Order Desk's recorded test selects products this way before Add to cart:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L35-L38 -->
```java
ui.tab("New order · Ada Shop Ltd").table().row("Product", "A4 paper, 500 sheets").cell("SKU").click();
ui.tab("New order · Ada Shop Ltd").button("Add to cart").click();
ui.tab("New order · Ada Shop Ltd").table().row("Product", "Gel pen, black").cell("SKU").click();
ui.tab("New order · Ada Shop Ltd").button("Add to cart").click();
```

- **Open a row:** `click` with `"count":2`; in Java `.doubleClick()` or `.open()`.
- **Add to the selection:** `"modifiers":["Mod"]`; in Java `.click("left", 0, "Mod")`.
- **Right-click a row:** `context_menu` with the row as target and the menu path (see [Menus and popups](/swing-ui-agent/guides/menus/)); in Java `.rightClick("Cancel orders…")`.
- **Sort:** click the header, as a user does:

```sh
$UI click '{"target":{"scope":["tab:Orders"],"role":"table","column":"Total"}}'
```

The next `read` returns the rows in the new order. A row found by its values does not care.

## Edit a cell

`type` on a cell clicks it, starts the edit, types and, with `"commit":true`, presses Enter:

```sh
$UI type '{"target":{"scope":["tab:New order · Ada Shop Ltd"],"role":"table","row":{"Product":"Gel pen, black"},"column":"Qty"},"text":"10","commit":true}'
```

```json
{"passed":true,"action":"type(table [tab:New order · Ada Shop Ltd] row={Product=Gel pen, black} column=Qty '10' +Enter)","message":"","ms":24}
```

The Qty cell's editor is a spinner; `type` finds the text field inside it. In the recorded test:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L41-L42 -->
```java
ui.tab("New order · Ada Shop Ltd").table().row("Product", "Gel pen, black").cell("Qty")
        .type("10", null, Boolean.TRUE);
```

- **A check box cell:** `check` with `"checked":true` or `false` clicks it only when it is not already in that state. In Java `.check()` and `.uncheck()`.
- **A value the editor refuses:** the edit is cancelled and the cell keeps its last valid value, so check the cell after typing. If the app explains the refusal in a dialog, the dialog comes back as `pending`. When another cell's editor refuses to let go, typing into this cell fails `NOT_ACTIONABLE` and says which cell is still being edited.
- **Paste a block:** `set_clipboard` with tab-separated cells and line-separated rows, click the anchor cell, then `press {"keys":"Mod+V"}`.

## Check

`expect` retries until the check holds (see [Waiting](/swing-ui-agent/guides/waiting/)). Give exactly one of these:

| Check | Locator part | Java |
| --- | --- | --- |
| A cell's text | `"cell":{"row":{"Order":"ORD-1640"},"column":"Status","expected":"Placed"}` | `.hasCell(row("Order", "ORD-1640"), "Status", "Placed")` |
| The row count | `"rows":{"op":"gte","value":1}` (`eq`, `gt`, `gte`, `lt`, `lte`) | `.hasRowCount("gte", 1)` |
| Exactly these rows selected | `"selected":[0]` (`[]` for none) | `.hasSelectedRows(0)` |
| A row selected | target with a `row`, `"state":["selected"]` | `assertThat(table.row(...)).isSelected()` |
| A cell editable | target with a `row` and `column`, `"state":["editable"]` | `.isEditable()` |
| Scrolled to the end | `"state":["atBottom"]` | `.hasStates("atBottom")` |

The recorded test checks the new order's row on the Orders tab:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L86-L89 -->
```java
ui.tab("Orders").click();

assertThat(ui.tab("Orders").table().cell("Status")).hasCell(row("Order", order), "Status", "Placed");
assertThat(ui.tab("Orders").table().cell("Status")).hasCell(row("Order", order), "Total", "€37.20");
```

`selected` holds row positions in view order, so it changes when the table is sorted. Check a row by its values where you can.

## Scroll

`scroll` moves the viewport directly, the way the scroll bar does: exact, and it fires the same change continuous paging listens for. Give exactly one of:

- `"to":"bottom"` (or `top`, `left`, `right`); in Java `.scrollTo("bottom")`;
- `"pages":2` (negative goes back); in Java `.scrollBy(2)`;
- a `row` on the target, to bring that row into view; in Java `.row(...).scrollIntoView()`.

```sh
$UI scroll '{"target":{"scope":["tab:New order"],"role":"table","column":"Stock"},"to":"bottom"}'
$UI expect '{"target":{"scope":["tab:New order"],"role":"table","column":"Stock"},"state":["atBottom"]}'
```

Use `scroll` rather than key presses such as `Ctrl+End`: those move the selection, not only the view.

## Several columns with one header

Some tables repeat a header, a pivot with one "Quantity" column per order, say. Tell them apart by the column's identifier: `"column":"Quantity@ORD-1640"`, in Java `.cell("Quantity", "ORD-1640")`.

## What's next

- [Menus and popups](/swing-ui-agent/guides/menus/): context menus on rows.
- [Trees](/swing-ui-agent/guides/trees/): the other element with rows.
