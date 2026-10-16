---
title: Dialogs and file choosers
description: Confirmations, wizards, option panes and file choosers.
sidebar:
  order: 7
---

A modal dialog stops the app until someone answers it, and it stops a careless test the same way. swing-ui-agent never waits on an open dialog: the step that opened it returns, describes it, and leaves the answer to the next step. This page shows confirmations, a wizard dialog and a file chooser on Order Desk.

The examples run from the Order Desk folder, with `UI="java -jar target/swing-ui-agent-agent.jar"`.

## A step that opens a dialog

Order Desk asks before it cancels an order. The step that asks comes back with the dialog under `pending`:

```sh
$UI context_menu '{"target":{"scope":["tab:Orders"],"role":"table","row":{"Order":"ORD-1637"}},"path":["Cancel orders…"]}'
```

```json
{"passed":true,"action":"contextMenu(table [tab:Orders] row={Order=ORD-1637} > [Cancel orders…])","message":"","ms":39,
 "pending":{"title":"Cancel order","message":"Cancel order ORD-1637? Stock is restored and invoice totals are taken off what the customer owes.","buttons":["Cancel order","Keep"],"fields":[]}}
```

(Trimmed.) `pending` names the title, the message, the buttons and the fields. While the dialog shows, a step aimed at the window behind it is refused:

```json
{"passed":false,"action":"click(button 'New order')","reason":"NOT_ACTIONABLE","message":"NOT_ACTIONABLE: blocked by dialog Cancel order"}
```

## Answer it

`answer` clicks the button with that text, or with `"escape"` presses Escape and requires the dialog to close:

```sh
$UI answer '{"button":"Keep"}'
$UI answer '{"button":"escape"}'
```

In Java: `ui.answer("Keep")`. `ui.modalShowing()` says whether a modal dialog shows, and `ui.describeModal()` returns its description.

Use `answer` for option panes (`JOptionPane`). Their buttons can carry a name the look and feel set, such as `OptionPane.button`, with their text only in `value`, so `{"role":"button","name":"Keep"}` does not find them; `answer` goes by the text.

Answer deliberately. A dialog that would send, delete or overwrite something is the test's decision, not something to click away.

## Work inside a dialog

Target a dialog's elements with `"window":"dialog"` (the showing modal dialog), `"window":"dialog:<title>"`, or a title prefix such as `"dialog:Checkout*"`. In Java: `ui.dialog()` and `ui.dialog("Cancel order")`.

Order Desk's checkout is a four-step wizard: Address, Shipping, Payment, Review. Opening it returns it as `pending`, with its fields:

```json
"pending":{"title":"Checkout — Ada Shop Ltd","message":"Address Shipping Payment","buttons":["Back","Next","Cancel"],"fields":["Name","Street","Postal code","City","Phone (optional)"]}
```

Then each step is ordinary targets in the dialog. A `batch` runs several steps in one call and stops at the first that does not pass:

```sh
$UI batch '{"steps":[
  {"verb":"click","target":{"window":"dialog","role":"button","name":"Next"}},
  {"verb":"type","target":{"window":"dialog","role":"text","name":"PO number"},"text":"PO-7781"},
  {"verb":"click","target":{"window":"dialog","role":"button","name":"Next"}}]}'
```

`PlaceOrderUiIT` walks the same wizard and checks each step:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/PlaceOrderUiIT.java#L50-L69 -->
```java
ui.tab("New order · Ada Shop Ltd").button("Checkout…").click();

assertThat(ui.dialog().field("Postal code")).hasValue("10119");
// …
ui.dialog().button("Next").click();

assertThat(ui.dialog().radio("Standard · 3–5 business days")).isChecked();
// …
ui.dialog().button("Next").click();

assertThat(ui.dialog().label("Enter the PO number")).isVisible();
assertThat(ui.dialog().button("Next")).isDisabled();
// …
ui.dialog().field("PO number").type("PO-7781");
ui.dialog().button("Next").click();

assertThat(ui.dialog().label("€37.20")).isVisible();
```

- **Radio buttons:** `click` the one to choose; check it with `"state":["checked"]`, in Java `.isChecked()`.
- **Combo boxes:** `select` with the item's text opens the popup and clicks the item: `{"target":{"window":"dialog","role":"combo","name":"Country"},"item":"Austria"}`; in Java `ui.dialog().combo("Country").select("Austria")`.
- **A button that stays disabled** until the form is valid is a check of its own: `"state":["disabled"]`, in Java `.isDisabled()`.
- **Same button, several sections:** the review step has three Edit buttons, one per titled section. Scope by the section: `{"window":"dialog","scope":["section:Payment"],"role":"button","name":"Edit"}`, in Java `ui.dialog().section("Payment").button("Edit")`.

A dialog that opens another dialog works the same way: Place order asks "Place order for €37.20?", and that confirmation comes back as `pending` in turn.

## File choosers

A `JFileChooser` is a dialog with its own step. Order Desk's order tab saves a receipt through one:

```sh
$UI click '{"target":{"scope":["tab:ORD-1640"],"role":"button","name":"Save receipt…"}}'
$UI read_file_chooser
```

```json
{"found":true,"folder":"/Users/you","name":"ORD-1640.txt","nameFocused":false,"lookAndFeel":"FlatLaf Light"}
```

(Order Desk opens the chooser in your home folder.) `read_file_chooser` returns the folder it shows, the file-name field's text, whether that field has focus, and the look and feel whose key bindings it uses. `choose_file` answers it: it types the path and presses Enter (falling back to the chooser's own approve, recorded as `via:api`):

```sh
$UI choose_file '{"path":"/tmp/ORD-1640.txt"}'
```

When the app asks before replacing an existing file, that question comes back as `pending`:

```json
{"passed":true,"action":"chooseFile(/tmp/ORD-1640.txt)","message":"",
 "pending":{"title":"Save receipt","message":"ORD-1640.txt already exists. Do you want to replace it?","buttons":["Replace","Cancel"],"fields":[]}}
```

- **Cancel the chooser:** `choose_file {"approve":false}` presses Escape.
- **In Java:** `ui.chooseFile("/tmp/ORD-1640.txt")`, `ui.chooseFile(path, false)` to cancel, and `ui.fileChooser()` for the folder, name and focus.

Use a path the test owns, such as a temporary folder, so a run never writes over a real file.

## Dialogs and the reset

Before each test, the reset presses Escape on every modal dialog and closes what does not go. A test never inherits a dialog from the one before; a dialog that will not close fails the reset with its title. See [Reset and the starting screen](/swing-ui-agent/guides/reset/).

## What's next

- [Waiting](/swing-ui-agent/guides/waiting/): why a step returns as soon as a dialog appears.
- [Menus and popups](/swing-ui-agent/guides/menus/): the menu items that open most dialogs.
