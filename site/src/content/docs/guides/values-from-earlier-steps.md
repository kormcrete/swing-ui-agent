---
title: Values from earlier steps
description: How a recorded test uses what an earlier step made, such as a new order's number, a value you remembered or a text unique per run. In the palette, in the file and in a Java test.
sidebar:
  order: 3
---

A recorded test is replayed again and again. When a step creates something, the app gives it a new number each time: the order placed today is `ORD-1640`, the next run's is `ORD-1641`. A later step that looks for the order by the number seen while recording would fail on the second run.

So a step can **use a value from an earlier step**. On every run it takes the value that step made in that run, never the one seen while recording. It all stays in the recorded test file: nothing to compile, and you never type a path or a formula. If a link cannot find its value, its step fails and says why. It never falls back to the recorded value.

Three things give a step its value:

- **A link** to what an earlier step changed: a row it added, a field it filled, a tab it opened.
- **Remember this:** a value that was already on the screen, kept under a name you give it.
- **Unique per run:** typed text that gets a short id that is new on every run, so two runs never type the same text.

## In the palette

### Links made for you

While you record, the recorder compares every value you use with what the earlier steps changed. A value can be one you click, type, pick or check. When it is new from an earlier step, the step is linked at once, with nothing for you to do.

An example on Order Desk. Place an order for Ada Shop Ltd, confirm it, open the **Orders** tab, then **Check** the new order's **Status** cell: *Status is "Placed" in this row*. The check finds its row by the order number, `ORD-1640`. That number is new: the **Place order** confirmation added that row to the Orders table. So the check is linked to it, and the next runs check `ORD-1641`, then `ORD-1642`.

In the **Steps** list a linked step shows 🔗 and where its value comes from:

```text
16  Confirm the order
17  Open the Orders list
    17.1  The new order is listed as Placed  🔗 from step 16: Confirm the order added
```

Point at the row to see the value it stood for: `(seen: ORD-1640)`. After a replay, it shows the value of that replay.

A value is linked automatically only when the link is surely right:

- It **appeared in that step**. It was nowhere on the screen before the step and is there after it.
- It **stands out**. It has 4 characters or more, is not yes, no, true or false, and is not a whole number below 1000. A quantity of `3` is never linked.
- It is **the whole value**, or a whole part of what you typed. `Copy of ORD-1640` is linked as the fixed `Copy of ` plus the number.
- A fresh value in the **title** of a tab or section (`tab:`, `section:`) or of a dialog or window (`dialog:`, `window:`) is linked like a name: a window titled `ORD-1640` becomes `window:${copy.rows['Orders › table'].added[0].Order}`. A title with data next to fixed words (`Order ORD-1640`) stays a `*` glob (`Order *`). A value in a scope step `id:` or `text:` or in a `column` stays exact, and the step result says so in `linkNote`.
- When one step made the same value in several places, a field comes first, then a row, an item, a plugin step's own value, and last a text.
- A title a step **opened** (a tab, a dialog, a window) is never linked by itself: most such titles are fixed words of the app, like *Invoice* or *History*. When the title is data, the same value is almost always in a row, a field or a text, which is linked instead. To link to the title, use **Use a value from an earlier step…**.
- A value is never linked through a name made of the value itself, such as a label known only by its own text: on the next run that name is another one. What a step typed is no source either: it is the same text on every run, so a check of a label that shows it stays exact and passes. Make the typed text **Unique per run** and the check follows it.
- A plugin's domain step gets its params linked like a typed value (`openOrder` with the new order's number). A domain **check** never has a param linked to what it reads itself: `cartTotalIs` reads the Total label, so its total stays exact unless another place, such as the new Orders row, holds it.
- A row or an item is linked only when the step **added (or changed) that one alone**. A search that filled a table with results is not, because the next run may list them in another order.
- When several earlier steps made the same value, the **most recent one** wins.

The recorder keeps watching until your next step. So a value the app shows a moment late, like an order number that arrives after a short wait, still counts for the step that made it. A replay waits for it in the same place: right after that step, before the next one runs, so what a later step changes never counts for it.

### Link it yourself

When a step was not linked, or you want another source, right click it in **Steps** (or **Shift+F10**) and choose **Use a value from an earlier step…**. It lists what the earlier steps changed and the values you remembered, in words, newest first, such as *Step 16, Confirm the order added to Orders › table: ORD-1640 · …*. Pick one. For typed text, the rest of the text stays as you typed it.

It also offers short values, which are never linked automatically. It is greyed when there is nothing to offer.

### Remember this

Sometimes the value you need was already on the screen: the order you opened from the list, a customer's account number. In Check mode, click it and choose **Remember this as…** at the end of the menu. A name field opens in the Steps list (`└ remember as`) with a name suggested from what you clicked. Change it or keep it; words are fine: `Order number` is kept as `orderNumber` (or `orderNumber2` when that name is taken), and the bar shows the name kept. **Enter** saves; **Esc** keeps nothing.

- It goes under the step before it, like a check (`5.1`), and it runs with that step.
- Later steps that use the value are linked to it, automatically or by hand. Their 🔗 reads `orderNumber (remembered)`.
- You can remember fields, labels, buttons, drop-downs, table cells, list entries, tree rows and tabs. You cannot remember check boxes, toggles, password fields, or a whole table or column.
- A table cell is found again by the other values in its row. If they do not tell the rows apart, it is found as the selected row, and otherwise by its place. A list entry, a tree row or a tab must be the selected one. If it is not, the menu line is greyed with what to do: "select the row first, or remember a field" for a list entry, "select the item first, …" for a tree row, "select the tab first, …" for a tab.
- It is greyed with "it is empty" when there is nothing to remember. It is greyed with "it is found by its own text" when the element is only found by the value you want to keep, such as a button or a label with no caption of its own. Remember a field or a cell that shows the value instead.
- On a replay it fails when what it reads is missing or empty.
- **An unnamed tab strip.** `{"role":"tabs"}` is ambiguous when a tab holds its own strip. Remember the value from a place that is unique instead: a table cell with `selected:true`, a field, a label. The main strip without an id is `{"role":"tabs","nth":0}` (counted from 0, in tree order); the step is marked `fragile`.

### Unique per run

A text you type that must be new on every run, such as a note or a customer's name that may not exist twice: right click the typing step, then **Unique per run** ▸ **Add at the end** or **Add at the start**. The run adds a short id of 8 letters and digits, new on every run (`Copied note k3x9a2bq`). **Off** takes it away.

- Nothing is typed again. The recording goes on with your text as you typed it.
- Later steps that use that text (they type it, find a row by it or check it) are linked to it at once, also when it is short. The 🔗 reads `unique per run`.
- Every run gets its own id. Two recorded tests never share one.

### Undo a link, delete, redo

- **Use the exact value** in the row menu turns a link back into the value it stood for. **↶ Undo** takes back any link, made by the recorder or by you.
- Deleting a step that others use asks first: "Steps 2 and 3 use what it changed. Delete it and keep their values as they are?". **Delete** keeps their values as exact values.
- **↺ Redo from selected** drops the later steps anyway, links included.

### Replays

- **Replay all** and **▶ Run** make every value fresh: new rows, new run ids.
- **▶ From selected** uses what the earlier steps made last in this session. That is the record now on the screen.
- If a step needs a value that no step made in this session, for example after loading a file and running it from the middle, the replay is refused before it starts: "Step 6 uses what step 3 changed; replay from step 3 or earlier".
- A run report shows each value a link stood for, after its step: `[17.1] PASS The new order is listed as Placed (answer.rows['Orders › table'].added[0].Order = ORD-1641)`.

## When a value is not linked

- **A new number that only its own label shows.** A check of the very label, field or cell that showed the new value is never linked to that change of its own: it would compare the value with itself. The same holds for a plugin's domain check, for a value remembered from that very place under the same step, and for a link by hand under the step that made or remembered the value. When the same value also came as a new row, a field elsewhere or a remembered value, the check **is** linked to that, and works on every run: the order tab's title and label link to the new Orders row. Only a value shown nowhere else stays exact, and fails on the next run. Remember it, or check something else.
- **A value label not tied to its caption.** When the app did not tie a caption to the value beside it (no `setLabelFor`, and no colon at the end of the caption), the caption is a label of its own and the value label is named by its own text. Say an app shows the caption `Warehouse` beside `North dock`: a check of the value is `{"role":"label","name":"North dock"},"shown":true`, and a value an earlier step made there is linked through its `name`; text you typed is linked only once it is unique per run.
- **A value in another form.** `ORD 1640` or `1640` where the step made `ORD-1640`, another date format, or a part of what the step made, such as the number in the status text `Order ORD-1640 placed`: none is linked, not even by hand. A recorded test holds no formulas. That is a [Java test](#in-a-java-test).
- **Rows from a step that added several**, such as search results: never linked, not even by hand, because the next run may list them in another order. Remember the value you need from the screen instead, then use it.
- **A place in a list a plugin step returned** (`.step.orders[3]`): never linked either, for the same reason. A value it returns by name or under a stable key (`.step.orders.ORD-1634.Customer`) is.
- **What the screen does not show**, such as a database or a log, and components the app paints itself, such as charts or a canvas. No step can read those.

## Ask the agent

The agent records the same way. Its steps get the same automatic links, and it can link, remember and make text unique with the `recording` tool:

```sh
$UI recording '{"op":"remember","target":{"role":"table","scope":["tab:Orders"],"column":"Order"},"selected":true,"as":"orderNumber"}'
$UI recording '{"op":"link","n":"17.1","ref":"orderNumber"}'
$UI recording '{"op":"unlink","n":"17.1"}'
$UI recording '{"op":"unique","n":5,"where":"end"}'
```

- `remember` reads what `target` shows now and keeps it under `as`, as a check under the last step (or under step `n`). It is one edit: one `undo` takes it back. `move` cannot move a check: delete it and insert it where it belongs (the file form needs no `action`).
- `link` makes step or check `n` use a value: a remembered name, or a step and a path into what it changed, such as `${16.rows['Orders › table'].added[0].Order}` (a path from `changed` in `recording list`), where the value sits. It is refused, in words, when the step does not hold that value, the path has none, the value is one of several rows a step added or sits at a place in a list a plugin step returned, or the path names the value itself (a label known by its own text: on the next run it has another name).
- A step from a loaded file holds the value of the run it was recorded on, not the one the source made in this session. Name the place with `field` and the link goes there, and to every other place of the step with that value: `{"op":"link","n":"17.1","field":"target.name","ref":"${16.rows['Orders › table'].added[0].Order}"}`.
- `edit` with a new `target` or `text` keeps a link where the new value is the one the link stood for; another value is taken as given.
- `unlink` turns the links back into the values they stood for; `unique` adds the run's id at the `end` or `start` of a typed text (`off` takes it away).
- A `do` step's own sentence follows its links: with the order number linked, "Open order ORD-1641" is saved as "Open order the number placeOrder made", back again after `unlink` or `undo`, while a sentence the agent wrote with `say` stays as written.

`recording list` shows, on each step, what it `changed` (the values a later step can link to: the paths later steps use first, at most 20, never one of several rows a step added or changed, nor a place in a list a plugin step returned; `changedMore` counts the ones left out), what it `uses`, which steps it is `usedBy`, and `unique`; on a remember entry, `remembered` (the value it read).

When a test is for a person, the agent keeps everything in the recorded test, with no Java. It links with these ops, names the steps with `edit` and `say`, groups them, and saves with `recording export` while it still fixes the test, and with `export_recorded_test` at the end. The file replays on its own, and you can change it in the palette; you never have to link or name a step yourself. The agent never types `${…}` into a step: what it types, and the `text` or `target` it gives `edit`, is taken as typed, and it links with `link`. A whole step it gives `insert` or `edit` is in the file's form, and an edit that would leave a step using a value no earlier step makes, or an id twice, is refused: the recording stays as it was. The [record-test skill](/swing-ui-agent/agents/skills/) teaches this.

## In the file

A recorded test that uses values from earlier steps is written as format version 4. One without them stays version 2 or 3, so older agents keep reading it. The two steps of the Order Desk example:

```json
{
  "action" : "answer",
  "id" : "answer",
  "say" : "Confirm the order",
  "button" : "Place order"
},
{
  "action" : "click",
  "say" : "Open the Orders list",
  "target" : { "role" : "tab", "name" : "Orders" },
  "checks" : [ {
    "say" : "The new order is listed as Placed",
    "target" : { "scope" : [ "tab:Orders" ], "role" : "table" },
    "cell" : {
      "row" : { "Order" : "${answer.rows['Orders › table'].added[0].Order}" },
      "column" : "Status",
      "expected" : "Placed"
    }
  } ]
}
```

- A step that others use gets an **`id`**, made from what it acted on (`copy`, `placeOrder`; `answer` for a dialog's answer). Only those steps have one. Ids survive renumbering.
- A **reference** is `${<id><path>}` inside a value: `<id>` is a step id, a remembered name or `run`, and `<path>` leads into what that step changed. A remembered value alone is `${orderNumber}`.
- **Run values:** `${run.id}` (8 letters and digits, new on every run), `${run.date}` (`yyyy-MM-dd`), `${run.time}` (`HH:mm:ss`, at the start of the run).
- **A remember entry** is a check with `"action": "remember"`, its `target` and the name under `as`. `"selected": true` reads the selected row, item or tab.
- `$${` writes a literal `${`. So a `$` right before a reference cannot be written (`$${total}` is the text `${total}`): keep such a text exact, or use the value without the `$` in front.

The fields, where references may appear, and what the reader refuses are on the [format page](/swing-ui-agent/reference/recorded-test-format/#values-from-earlier-steps).

### What a step changed

What a step changed is its **outcome**: a JSON object with only what changed, keyed by the names the person sees. The `answer` step above, shortened:

```json
{
  "opened" : { "tabs" : [ "ORD-1640", "Items", "Delivery", "Invoice" ] },
  "closed" : { "dialogs" : [ "Checkout — Ada Shop Ltd", "Place order" ], "tabs" : [ "New order · Ada Shop Ltd" ] },
  "texts" : { "Placed" : "Placed", "Order ORD-1640 placed" : "Order ORD-1640 placed", "…" : "…" },
  "rows" : {
    "Orders › table" : {
      "added" : [ { "Order" : "ORD-1640", "Date" : "12 Oct 2026", "Customer" : "Ada Shop Ltd",
                    "Items" : "13", "Total" : "€37.20", "Status" : "Placed" } ],
      "removed" : [ ], "changed" : [ ], "count" : 13
    }
  }
}
```

| Key | What it holds |
| --- | --- |
| `opened`, `closed` | `windows`, `dialogs` and `tabs` by title. |
| `fields` | Values of text fields (also a spinner's), drop-downs, check boxes, radio buttons and toggles that changed, by their label; a check box as `true` or `false`. |
| `texts` | Labels and buttons whose text changed. |
| `rows` | Per table: `added`, `removed`, `changed` (`{before, after}`) rows, each as `{column: shown text}`, and `count` after. |
| `items` | Per list or tree: `added` and `removed` entries; a tree row as its path, `Folders > Folder 3`. |
| `selected` | The rows or items now selected. |
| `message` | The text of a message dialog that opened. |
| `step` | What a plugin's domain step returned (see [Plugin vocabulary](/swing-ui-agent/plugins/vocabulary/#values-a-step-makes)). |
| `partial` | `true` when reading ran out of time (see [Limits](/swing-ui-agent/project/limits/#values-from-earlier-steps)). |

A name that two elements share gets the tabs and sections around it, `Billing › Name`. An unnamed table is `table`; with two of them, `Orders › table` is the one on the Orders tab. Paths quote such keys: `rows['Orders › table']`. At most 50 entries per list are kept (`count` stays true), and texts are cut at 500 characters.

An agent gets the outcome of any step with `"outcome": true` on `do_action`, `batch` steps and the step tools. The result then has `outcome` and an `outcomeHandle`. `read {"form":"outcome","handle":N}` reads the change again later, for a value the app shows late. The agent keeps the last 20.

## In a Java test

The same recording exported as a Java test keeps the links as code:

```java
Outcome answer = ui.outcomeOf(() -> ui.answer("Place order"));

// Open the Orders list
ui.tab("Orders").click();
// The new order is listed as Placed
assertThat(ui.tab("Orders").table())
        .hasCell(row("Order", answer.text("$.rows['Orders › table'].added[0].Order")), "Status", "Placed");
```

- `ui.outcomeOf(() -> one action)` reads the app before and after that one action and returns its `Outcome`. Only actions inside it pay for the reading. It throws when the lambda runs no action or more than one.
- `Outcome.text("$…")` gives the one value at a JSON path, `number("$…")` a whole number, `read("$…")` every match as JSON nodes, and `json()` the whole object. Paths start at `$`; a key with spaces, dots or quotes goes in brackets: `['Order no.']`.
- `text`, `number` and `read` read the app again until the path has its value or the client's wait ends (10 seconds by default), as a link in a recorded test waits. So a test that asks an outcome for something that is not there waits the whole time. Check that something is gone on the screen instead: `assertThat(ui.tab("ORD-1640")).isAbsent()`.
- Run values: `ui.run().id()`, `date()` and `time()`. There is one set per `@UiTest` method, shared by all its clients.
- A remembered value is a `String` local: `String orderNumber = ui.tab("Orders").table().cell("Order").selectedText();`. `shownText()` reads what an element shows, as a remember step does. `text()` stays the element's name, such as a field's caption.

A Java test can go further than the file: cut the number out of `Order ORD-1640 placed`, loop, branch, or ask a database. See [Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/).

## What's next

- [Recorded test format](/swing-ui-agent/reference/recorded-test-format/#values-from-earlier-steps)
- [Sentences and groups](/swing-ui-agent/guides/sentences/)
- [Limits](/swing-ui-agent/project/limits/#values-from-earlier-steps)
