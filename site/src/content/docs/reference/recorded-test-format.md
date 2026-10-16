---
title: Recorded test format
description: The fields of a recorded test file, version 2, how steps and checks are numbered, and what the reader refuses.
sidebar:
  order: 4
---

A recorded test is a JSON file in the recorded tests folder (`uitest` by default), `uitest/<name>.json`. For how to save, open and run one, see [Run and edit a recorded test](/swing-ui-agent/start/recorded-tests/). This page is the format.

The agent writes and reads the file; you rarely edit it by hand. It holds exact values only: the text a person saw. Values that change per run, or are read from the app, belong in a [Java test](/swing-ui-agent/start/choose-test-kind/).

## An example

The file `gel-pen-total.json` from [Run and edit a recorded test](/swing-ui-agent/start/recorded-tests/#save-one), recorded on Order Desk:

```json
{
  "version" : 2,
  "name" : "gel-pen-total",
  "description" : "Starts an order for Ada and adds Gel pen, black; the cart total reads 1.44.",
  "start" : "reset",
  "steps" : [
    { "action" : "menu", "path" : [ "File", "New order" ] },
    { "action" : "type", "target" : { "role" : "text", "name" : "Customer" }, "text" : "Ada" },
    { "action" : "press", "keys" : "Enter" },
    { "action" : "click", "target" : { "role" : "table", "row" : { "Product" : "Gel pen, black" }, "column" : "SKU" } },
    { "action" : "click", "target" : { "role" : "button", "name" : "Add to cart" },
      "checks" : [ { "target" : { "role" : "label", "name" : "Total" }, "text" : "€1.44" } ] }
  ]
}
```

The tool writes one field per line; short objects are joined here. With sentences and a group, the same test reads as in [Sentences and groups](/swing-ui-agent/guides/sentences/#in-the-file).

## Top-level fields

| Field | Meaning |
| --- | --- |
| `version` | Required. A whole number, 1 or more. This agent reads and writes `2`; an older or a newer one is refused (see below). |
| `name` | Optional text. A file without one is named after the file. |
| `description` | Optional text. Shown in the palette's **Load recorded test…** list. |
| `start` | Required. `"reset"`: every run begins with the app reset. The only start there is. |
| `steps` | Required. A non-empty array of steps and groups. |

A test name is 1 to 100 characters: letters, digits, space, `.`, `_`, `-`; it starts with a letter or digit, does not end in `.` or a space, and is not a Windows device name (`CON`, `PRN`, `AUX`, `NUL`, `COM1` to `COM9`, `LPT1` to `LPT9`).

## A step

A step is an object with an `action` and the fields of that action: the same step JSON as `do_action` takes (see [Tools](/swing-ui-agent/reference/tools/)). Two more fields are common to all:

- **`say`:** optional. A one-line sentence for the step. See [Sentences and groups](/swing-ui-agent/guides/sentences/).
- **`checks`:** optional, not empty when present. The checks that run after the step.

A check is an `expect` step without the `"action"`: its `target` and what it expects (`text`, ...), and an optional `say`. Checks go under the step they check, never as steps of their own. A step that is `"action": "expect"` is refused.

The first step may hold only `checks`: `{"checks": [...]}`. They run before any action, for example on the screen after the reset. It cannot have a `say` of its own; put the sentence on a check. No other step may be check-only.

## A group

A group is `{"say": "...", "steps": [...]}`. It needs a `say` and two or more steps. A group does not hold another group, and has no other field.

## Numbering

- Steps are numbered `1`, `2`, ... in the order they run.
- A check is numbered `n.k`: check `k` of step `n`, such as `5.1`.
- A check-only first step is step `1`; the next step is `2`.
- A group has no number and takes none: its steps are numbered as if it were not there.
- Counts are in steps. A check is not counted.

The palette, the agent's `recording` list, run reports and the refusals below use the same numbers.

## What is refused

The reader is strict: a typo never silently drops what a step means. Every refusal is `BAD_INPUT`. A file that cannot be read at all fails with `cannot read recorded test file <path>: …`; every other refusal starts with the file or the `test` argument it came from.

- **A newer version** is refused with the reason: `is format version 3, written by a newer swing-ui-agent; this one reads version 2 - update the agent`.
- **An older one** (version 1, one flat list) is refused too: export it again, or nest each check under the step before it.
- **An unknown field** on the file, a step, a check or a group, with the fields it knows: `step 1 (press): unknown field 'colour'; known: [fragile, keys, say, target, timeout, via]`.
- **`params`, `capture`:** `a recorded test holds exact values only; values per run or read from the app belong in a Java test`.
- **Fields and step types of later formats** (`requires`, `author`, `do`) are refused with `belongs to a newer recorded test format; update swing-ui-agent`.
- **A `start` other than `reset`**, a missing `steps`, an empty `steps`.
- **A check-only step that is not step 1**, an `expect` as a step, an empty `checks`.
- **A group** without a `say`, with fewer than two steps, or inside another group.
- **A `say`** that is empty, has a line break, or is longer than 200 characters.

## Keep reading

- [Sentences and groups](/swing-ui-agent/guides/sentences/)
- [Run and edit a recorded test](/swing-ui-agent/start/recorded-tests/)
