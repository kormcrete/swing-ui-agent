---
title: Targets and locators
description: "How a step finds an element: by what a user sees."
sidebar:
  order: 1
---

Every step that acts or checks needs a target. A target says what a user would say: the button with this text, the field with this label, the row whose Order is `ORD-1640`. This page shows how a target is written, how the driver matches it, and what you get back when it does not match.

The examples run against [Order Desk](/swing-ui-agent/demo/tour/) from its folder, with:

```sh
UI="java -jar target/swing-ui-agent-agent.jar"
```

## A locator

On the command line and over MCP a target is a JSON object, the locator. Give at least one of `role`, `name` or `id`:

```json
{"scope":["tab:New order · Ada Shop Ltd"],"role":"button","name":"Add to cart"}
```

- **`role`:** what kind of element it is: `button`, `toggle`, `checkbox`, `radio`, `text`, `password`, `combo`, `list`, `table`, `tree`, `tabs`, `tab`, `menubar`, `menu`, `menuitem`, `label`, `clickable` (a label the app made clickable), `section` (a titled border), `window`, `dialog`.
- **`name`:** the element's name, as described below. `"match":"contains"` matches a part of it.
- **`id`:** the component's `getName()`, when the app sets one.
- **`window`:** `main`, `dialog`, `popup`, `dialog:<title>` or `window:<title>`. A title ending in `*` matches a prefix.
- **`scope`:** the containers the element sits in, outermost first: `tab:<title>`, `section:<caption>`, or `id:<name>` (strictly inside the component with that `getName()`).
- **Parts of an element:** `row`, `column`, `item` and `point`, covered in [Tables](/swing-ui-agent/guides/tables/) and [Trees](/swing-ui-agent/guides/trees/). In a recorded test, a row is kept by its value or by its position: see [Sentences and groups](/swing-ui-agent/guides/sentences/#how-a-table-row-is-kept).
- **`nth`:** which match to take when several match. A last resort: see [Prefer a stable target](#prefer-a-stable-target).

A test written by `export_test` uses the same locators through the `Client` API, one call per level:

| Locator | Java |
| --- | --- |
| `{"role":"button","name":"Checkout…"}` | `ui.button("Checkout…")` |
| `{"scope":["tab:New order"],"role":"text","name":"Customer"}` | `ui.tab("New order").field("Customer")` |
| `{"window":"dialog","role":"radio","name":"Express · next business day"}` | `ui.dialog().radio("Express · next business day")` |
| `{"window":"dialog","scope":["section:Payment"],"role":"button","name":"Edit"}` | `ui.dialog().section("Payment").button("Edit")` |
| `{"role":"label","name":"placed","match":"contains"}` | `ui.label("placed").contains()` |
| `{"id":"customerField"}` | `ui.byId("customerField")` |

`field` is the `text` role. A tab or a section in a chain becomes a `scope` step, and a bare `byId(...)` in a chain becomes an `id:` scope step.

## What counts as a name

The driver reads names from the Swing components themselves, never through the accessibility API. The first of these wins:

1. The component's own `getName()`, when the app (or its look and feel) set one.
2. Its own text: a button's or a label's text.
3. A caption label: the label linked with `setLabelFor`, else a label stacked right above it, else the nearest label on its row. A label that names a field is not a node of its own: ask for the field, `{"role":"text","name":"Customer"}`, not for the label.
4. For a table, a linked label on the panel that wraps it.
5. A titled border around it.
6. Its tooltip. Order Desk's ▾ button has no text, so its name is its tooltip, `New order for a recent customer`.
7. Its action's name.

A tab's name is its title. Only showing elements match: the content of a tab that is not selected is not found until the tab is.

Order Desk sets no component names, so every target on these pages uses what a user sees.

## Find the target in a snapshot

Look before you act. A scoped `snapshot` lists what is on screen with role, name, value and state:

```sh
$UI snapshot '{"window":"main","depth":4,"compact":true}'
```

```json
{"ref":"e7","role":"button","name":"New order"},
{"ref":"e8","role":"button","name":"New order for a recent customer","tooltip":"New order for a recent customer"},
{"ref":"e10","role":"tab","name":"Orders","state":["selected"],"children":[
  {"ref":"e11","role":"text","name":"Search","value":"","state":["editable"]},
  {"ref":"e12","role":"combo","name":"Status","value":"All","state":["readonly"],"rowCount":4,"truncated":true},
  {"ref":"e13","role":"table","state":["atTop","atBottom"],"columns":["Order","Date","Customer","Items","Total","Status"],"rowCount":12,"selected":[],"truncated":true}]}
```

(Trimmed.) Each node has a `ref`. `{"ref":"e13"}` works as a target while you explore, but only until the next snapshot. A step aimed by ref is recorded with a locator the recorder derives: read it in the step's `recorded`.

Never take an unscoped snapshot of a real app: it is every window, and it costs thousands of tokens. Scope it with `window`, a `target`, `depth` and `compact`. When the answer ends with `"scoped":false`, scope the next one.

## When a target does not match

A step waits for its target to appear and become actionable, 10 seconds by default (see [Waiting](/swing-ui-agent/guides/waiting/)). If it still does not match, the step fails and says what does exist:

```sh
$UI click '{"target":{"role":"button","name":"New"}}'
```

```json
{"passed":false,"action":"click(button 'New')","reason":"NOT_FOUND","message":"NOT_FOUND: nothing matches button 'New'","details":["button 'New order' in main","button 'New order for a recent customer' in main"],"ms":10078}
```

When several elements match, the step fails `AMBIGUOUS` and lists them with the scope that tells them apart. The checkout's review step has three Edit buttons:

```sh
$UI click '{"target":{"window":"dialog","role":"button","name":"Edit"}}'
```

```json
{"passed":false,"action":"click(button 'Edit' in dialog)","reason":"AMBIGUOUS","message":"AMBIGUOUS: 3 elements match button 'Edit' in dialog; add a scope, a window or nth","details":["button 'Edit' in dialog:Checkout — Ada Shop Ltd [section:Delivery address]","button 'Edit' in dialog:Checkout — Ada Shop Ltd [section:Shipping]","button 'Edit' in dialog:Checkout — Ada Shop Ltd [section:Payment]"]}
```

Add the scope it names: `{"window":"dialog","scope":["section:Payment"],"role":"button","name":"Edit"}`.

A target that matches but cannot be used fails `NOT_ACTIONABLE` and says why: disabled, hidden, covered, or blocked by a modal dialog.

```json
{"passed":false,"action":"click(button 'Add'~)","reason":"NOT_ACTIONABLE","message":"NOT_ACTIONABLE: disabled: Add to cart"}
```

All three are the app's answer, so the call itself succeeds (exit code 0) and `passed` is false. Read `passed` on every result.

## Prefer a stable target

- **An `id` beats a label** when the app sets one: it survives rewording and translation. Find the element by its label, then keep its `id`.
- **Leave data out of names.** A tab titled `ORD-1640` is that order's tab today; the next run places `ORD-1641`. Read the number from the app, as Order Desk's [`PlaceOrderUiIT`](/swing-ui-agent/demo/recorded-test/) does with the status bar, or match a stable part with `contains`.
- **Scope instead of `nth`.** `nth` picks the n-th match in tree order. The step is marked `fragile`, and it hits the wrong element as soon as the layout changes. A scope, a window or a column says which one you mean.
- **Two inputs with one label is a bug to report.** When a label could name two fields, the driver refuses to guess. The fix is `setLabelFor(field)` in the app.

## What a locator cannot reach yet

- **A message right under its field.** A label placed directly below a field and aligned with it can be taken for the field's caption and left out of the tree. Order Desk's "Postal code is required" is not found this way.
- **The close button on a closable tab.** It has no node of its own. Close a tab through the app's menu or shortcut (in Order Desk, `File ▸ Close tab` or `Mod+W`).
- **Names a look and feel sets.** A component the look and feel named shows that name: FlatLaf's search-field clear button is `TextField.clearButton`, and option-pane buttons are `OptionPane.button`, with their text in `value`. Answer option panes with `answer`, which uses the button text (see [Dialogs and file choosers](/swing-ui-agent/guides/dialogs/)).

## What's next

- [Waiting](/swing-ui-agent/guides/waiting/): how long a step waits for its target and for the app.
- [Tables](/swing-ui-agent/guides/tables/): rows, columns and cells.
- [Tools](/swing-ui-agent/reference/tools/): every parameter of every tool.
