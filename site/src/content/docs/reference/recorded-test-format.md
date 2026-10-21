---
title: Recorded test format
description: The fields of a recorded test file, versions 2 to 4, values from earlier steps, how steps and checks are numbered, and what the reader refuses.
sidebar:
  order: 4
---

A recorded test is a JSON file in the recorded tests folder (`uitest` by default), `uitest/<name>.json`. For how to save, open and run one, see [Run and edit a recorded test](/swing-ui-agent/start/recorded-tests/). This page is the format.

The agent writes and reads the file; you rarely edit it by hand. It holds the values a person saw, and, from version 4, steps that use a value an earlier step made, a value remembered from the screen or a text unique per run (see [Values from earlier steps](#values-from-earlier-steps)). Values computed from others, loops and branches belong in a [Java test](/swing-ui-agent/start/choose-test-kind/).

## An example

The file `place-order.json`, recorded on Order Desk: a new order for Ada Shop Ltd, through checkout to the placed order. Its first step and the step that confirms the order, with its checks (17 steps in all; `// …` stands for what is left out):

<!-- snippet: uitest/place-order.json#L1-L325 -->
```json
{
  "version" : 2,
  "name" : "place-order",
  "description" : "A new order for Ada Shop Ltd, from File ▸ New order through checkout to the placed order",
  "start" : "reset",
  "steps" : [
    {
      "action" : "menu",
      "say" : "Open a new order",
      "path" : [
        "File",
        "New order"
      ]
    },
    // …
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
        }
      ]
    },
    // …
```

The tool writes one field per line. Steps can be put in groups; see [Sentences and groups](/swing-ui-agent/guides/sentences/#in-the-file).

## Top-level fields

| Field | Meaning |
| --- | --- |
| `version` | Required. A whole number, 1 or more. This agent reads `2` to `4`. It writes `2`; `3` when the test holds `do` steps; `4` when a step has an `id`, a `remember` entry or a reference. An older or a newer one is refused (see below). |
| `name` | Optional text. A file without one is named after the file. |
| `description` | Optional text. Shown in the palette's **Load recorded test…** list. |
| `start` | Required. `"reset"`: every run begins with the app reset. The only start there is. |
| `requires` | Version 3 only, required when the test holds `do` steps: the names of the plugins they come from, such as `["order-desk"]`. The agent writes it from the steps. |
| `steps` | Required. A non-empty array of steps and groups. |

A test name is 1 to 100 characters: letters, digits, space, `.`, `_`, `-`; it starts with a letter or digit, does not end in `.` or a space, and is not a Windows device name (`CON`, `PRN`, `AUX`, `NUL`, `COM1` to `COM9`, `LPT1` to `LPT9`).

## A step

A step is an object with an `action` and the fields of that action: the same step JSON as `do_action` takes (see [Tools](/swing-ui-agent/reference/tools/)). These fields are common to all:

- **`say`:** optional. A one-line sentence for the step. See [Sentences and groups](/swing-ui-agent/guides/sentences/).
- **`checks`:** optional, not empty when present. The checks that run after the step.
- **`id`:** version 4 only. The name later steps use to reach what this step changed; see [Values from earlier steps](#values-from-earlier-steps).

A check is an `expect` step without the `"action"`: its `target` and what it expects (`text`, ...), and an optional `say`. What it expects is one of `state`, `text`, `value`, `tooltip`, `rows`, `cell`, `selected`, `shown` (`true`: the element is there and shown) or `absent` (`true`: the element is gone, with a locator, not a ref; for example a closed tab). Checks go under the step they check, never as steps of their own. A step that is `"action": "expect"` is refused.

A `do` step (version 3) runs one of a plugin's domain steps with the values it is given (in version 4 they may hold references): `{"action": "do", "step": "order-desk:addToCart", "params": {"product": "Gel pen, black"}}`. The agent names the plugin before the step's id. A domain check sits in `checks` and keeps its `"action": "do"`. A recorded test can hold any domain step; only an export as a Java test needs the plugin to give the step a Java form.

The first step may hold only `checks`: `{"checks": [...]}`. They run before any action, for example on the screen after the reset. It cannot have a `say` of its own; put the sentence on a check. No other step may be check-only.

A `remember` entry (version 4) sits in `checks` with `"action": "remember"`: its `target`, the name to keep the value under in `as`, and `"selected": true` to read the selected row, item or tab. It is numbered like a check.

## A group

A group is `{"say": "...", "steps": [...]}`. It needs a `say` and two or more steps. A group does not hold another group, and has no other field.

## Values from earlier steps

Version 4. A value in a step may hold a **reference**, `${<id><path>}`, that the run fills in just before the step:

```json
{ "action" : "answer", "id" : "answer", "button" : "Place order" },
{ "action" : "click", "target" : { "role" : "tab", "name" : "Orders" },
  "checks" : [ { "target" : { "scope" : [ "tab:Orders" ], "role" : "table" },
                 "cell" : { "row" : { "Order" : "${answer.rows['Orders › table'].added[0].Order}" },
                            "column" : "Status", "expected" : "Placed" } } ] }
```

- **`<id>`** is one of:
  - an earlier step's `id`, then `<path>` leads into what that step changed (its outcome, see [What a step changed](/swing-ui-agent/guides/values-from-earlier-steps/#what-a-step-changed));
  - an earlier `remember` entry's `as` name, alone: `${orderNumber}`;
  - `run`: `${run.id}` (8 letters and digits, new on every run), `${run.date}` (`yyyy-MM-dd`), `${run.time}` (`HH:mm:ss`, at the start of the run).
- **`<path>`** is `.key`, `['key with spaces, dots or quotes']`, `[n]` (from 0) or `[-n]` (from the end).
- **Where:** in values, such as typed text, the values of a `row`, an `item`, a locator's `name` (such as a tab title), picked and selected values, what a check expects (`text`, `value`, `cell`), and the `params` of a `do` step. Never in `say`, `action`, `id`, `as`, `window`, `role`, `scope`, `column`, `match` or `ref`.
- **Ids:** a letter, then up to 39 letters, digits or `_`; each id or remember name once; `run` is taken. Only steps that others use carry one.
- **`$${`** is a literal `${`. In a version 2 or 3 file, `${` is plain text.
- A reference that cannot be filled fails its step with the source in words, for example `Step 3 (Copy) added no row to Orders`, after waiting as a check waits (10 seconds by default). It never falls back to the value seen while recording.
- A `params` number of a `do` step that holds a reference is checked when the step runs.

## Numbering

- Steps are numbered `1`, `2`, ... in the order they run.
- A check is numbered `n.k`: check `k` of step `n`, such as `5.1`.
- A check-only first step is step `1`; the next step is `2`.
- A group has no number and takes none: its steps are numbered as if it were not there.
- Counts are in steps. A check is not counted.

The palette, the agent's `recording` list, run reports and the refusals below use the same numbers.

## What is refused

The reader is strict: a typo never silently drops what a step means. Every refusal is `BAD_INPUT`. A file that cannot be read at all fails with `cannot read recorded test file <path>: …`; every other refusal starts with the file or the `test` argument it came from.

- **A newer version** is refused with the reason: `is format version 5, written by a newer swing-ui-agent; this one reads version 4 - update the agent`.
- **An older one** (version 1, one flat list) is refused too: export it again, or nest each check under the step before it.
- **An unknown field** on the file, a step, a check or a group, with the fields it knows: `step 1 (press): unknown field 'colour'; known: [fragile, id, keys, say, target, timeout, via]`.
- **`params`, `capture`:** `a recorded test takes values from earlier steps with ${...} references (format 4), not 'capture'/'params'`.
- **Fields of later formats** (`author`) are refused with `belongs to a newer recorded test format; update swing-ui-agent`.
- **`id` or `remember` before version 4:** `needs format version 4 ("version": 4), the format of a recorded test that uses values from earlier steps`.
- **A reference to a name no earlier step has:** `step 4 (click) (line 52): ${copy.rows.Orders.added[0].Number} refers to 'copy', but no earlier step is named 'copy'`. So are an unclosed reference, a path that does not parse, an id that is not valid or used twice, `run` as an id, and a `remember` with an `id` or without `as`.
- **`do` and `requires` in a version 2 file:** `needs format version 3`. A version 3 file with `do` steps needs `requires`, and names each plugin once; `requires` without `do` steps is refused too.
- **A `start` other than `reset`**, a missing `steps`, an empty `steps`.
- **A check-only step that is not step 1**, an `expect` as a step, an empty `checks`.
- **A group** without a `say`, with fewer than two steps, or inside another group.
- **A `say`** that is empty, has a line break, or is longer than 200 characters.

When the agent runs or loads a test with `do` steps, it also checks them against the plugins. A plugin that `requires` names and that is not ready is `CAPABILITY_GAP`. A step or param the plugin does not offer, or a domain check outside `checks`, is `BAD_INPUT` naming the step, its line in the file and the plugin.

## Keep reading

- [Sentences and groups](/swing-ui-agent/guides/sentences/)
- [Values from earlier steps](/swing-ui-agent/guides/values-from-earlier-steps/)
- [Run and edit a recorded test](/swing-ui-agent/start/recorded-tests/)
