---
title: Sentences and groups
description: Name steps and checks in words a person reads, group steps, say how a table row is kept, and what Java export does with them.
sidebar:
  order: 2
---

A recorded test shows its steps as the technical line (`click(button 'Add to cart')`). A **sentence** replaces that line with words a person reads: "Add it to the cart". It is optional and changes nothing about what the test does. A **group** puts several steps under one sentence.

A sentence is only words: it changes nothing about the values a step uses or checks. How a step uses a value an earlier step made is in [Values from earlier steps](/swing-ui-agent/guides/values-from-earlier-steps/).

## Write sentences in the Steps list

In the palette, **Steps** lists the recording, numbered, checks under their step.

- **Name a step or a check:** select it and double click, or press **F2** or **Enter**. A field opens over the row. **Enter** saves, **Esc** cancels. Clearing it removes the sentence (not a group's: a group always has one, and **Ungroup** ends it).
- **From the menu:** a right click on the row (or **Shift+F10**, or the context-menu key), then **Describe this step…** or **Describe this check…**. It opens the same field.
- **Name several steps:** select them and use **Describe these steps…**. They become a group, with one header row for its sentence. Do it again on the same steps to rename it.
- **Ungroup:** the row menu's **Ungroup** ends the group; its steps stay.

A sentence is one line of at most 200 characters.

## Ask the agent

The agent names on request: "name the steps" or "describe the test". It uses the `recording` tool, which edits what the person sees. Both of you work on one list and one undo history, and the person can change or undo what the agent wrote.

```sh
$UI recording '{"op":"edit","n":2,"say":"Enter the customer Ada"}'
$UI recording '{"op":"edit","n":"5.1","say":"The cart total is 1.44"}'
$UI recording '{"op":"group","from":1,"to":3,"say":"Start an order for Ada"}'
```

- `edit` with `n` a step number (`2`) or a check label (`"5.1"`) sets its `say`; `""` removes it.
- `group` takes steps `from` to `to` (two or more, whole steps, no nesting, no overlap); the same range again renames it. `ungroup {"n":2}` ends the group that holds step 2.
- Every edit returns the new list, with each step's `say` and the `groups`. Pass `rev` from your last list so an edit is refused if the person changed the recording meanwhile.

## In the file

The test from [Run and edit a recorded test](/swing-ui-agent/start/recorded-tests/#save-one), after those edits and the sentence on step 1, exported with `recording {"op":"export","as":"recorded",…}`:

```json
{
  "version" : 2,
  "name" : "gel-pen-sentences",
  "description" : "Starts an order for Ada and adds Gel pen, black; the cart total reads 1.44.",
  "start" : "reset",
  "steps" : [
    { "say" : "Start an order for Ada",
      "steps" : [
        { "action" : "menu", "say" : "Open a new order", "path" : [ "File", "New order" ] },
        { "action" : "type", "say" : "Enter the customer Ada", "target" : { "role" : "text", "name" : "Customer" }, "text" : "Ada" },
        { "action" : "press", "keys" : "Enter" }
      ] },
    { "action" : "click", "say" : "Pick Gel pen, black in the product table",
      "target" : { "role" : "table", "row" : { "Product" : "Gel pen, black" }, "column" : "SKU" } },
    { "action" : "click", "say" : "Add it to the cart", "target" : { "role" : "button", "name" : "Add to cart" },
      "checks" : [ { "say" : "The cart total is 1.44", "target" : { "role" : "label", "name" : "Total" }, "text" : "€1.44" } ] }
  ]
}
```

The tool writes one field per line; short objects are joined here. The group has no number: its steps are still 1, 2 and 3. A step without a sentence (step 3) shows its technical line. The fields are listed in the [format reference](/swing-ui-agent/reference/recorded-test-format/).

## How a table row is kept

When a step clicks or checks a table row, the file keeps the row one of two ways:

- **By this value:** `"row" : { "Product" : "Gel pen, black" }`. The text of a column that tells the rows apart. It still finds the row after the table is sorted or a row is added.
- **By position:** `"row" : { "index" : 4 }`. The row's place on screen, whatever its data.

Choose in the row menu: **Row: this value (…)** or **Row: by position (row 5)** (positions count from 1 in the menu, from 0 in the file). The current one is marked. A way that is not known is greyed: a value needs a column whose values are unique; a position is learned by one passing replay (the menu says "replay it once"). The agent uses `recording {"op":"row","n":4,"by":"position"}` (`"value"` for the other). Changing to the way it already has is refused.

Prefer the value where the row is data the test knows (an order number, a product). Use the position for "the first row".

## In a Java test

Exporting the same recording as Java turns each sentence into a `ui.step("…")` line above the code it names, a group's sentence first. Each one starts a part of the test's run journal. A check's sentence stays a comment above its assertion:

```java
    @Test
    void gelPenTotal(Client ui) {
        ui.step("Start an order for Ada");
        ui.step("Open a new order");
        ui.menu("File", "New order");

        ui.step("Enter the customer Ada");
        ui.field("Customer").type("Ada");
        ui.press("Enter");

        ui.step("Pick Gel pen, black in the product table");
        ui.table().row("Product", "Gel pen, black").cell("SKU").click();

        ui.step("Add it to the cart");
        ui.button("Add to cart").click();
        // The cart total is 1.44
        assertThat(ui.label("Total")).hasText("€1.44");
    }
```

Left out: the imports and the class around the method.

## What's next

- [Values from earlier steps](/swing-ui-agent/guides/values-from-earlier-steps/)
- [Recorded test format](/swing-ui-agent/reference/recorded-test-format/)
- [Tables](/swing-ui-agent/guides/tables/) for rows by value and by position in a locator.
