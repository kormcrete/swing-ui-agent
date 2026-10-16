---
title: Record together with your agent
description: Tell your agent what to do in chat; it records the test while you watch in the palette, and you both edit one list.
sidebar:
  order: 9
---

You do not have to touch the app. You say in chat what the test does; the agent drives Order Desk, records each step and each check, and saves the test. You watch the steps appear in the palette and can delete, redo or undo any of them. The agent and you work on **one recording**, with one history.

This is one of three ways to get a recorded test. [Record a test by showing it](/swing-ui-agent/start/show/) is you doing it with the palette. [Record a test with the agent](/swing-ui-agent/start/record/) is the agent recording it from your words alone. Whether a recorded test or a Java test is the right one: [Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/).

You will learn how to:

- ask for a recording and read what the agent does;
- delete, redo and undo steps while the agent works;
- have the agent name the steps;
- save the test and run it.

## Before you start

- Order Desk running with the palette on (it is by default), and the agent's skills installed: see [Record and run a UI test with Claude Code](/swing-ui-agent/start/claude-code/).
- The commands below call `$UI`, the agent jar with the port, as in [Let your agent drive the app](/swing-ui-agent/start/drive/). You type none of them; this is what the agent runs.

## 1. Say what the test does

In chat:

> Record a test: open a new order for Ada, add 3 packs of A4 paper, check the cart total.

The agent starts a recording that begins at a reset, so the test gets to its own starting screen:

```sh
$UI recording '{"op":"start","reset":true}'
```

```json
{"state":"recording","startedBy":"agent","startedAt":"2026-10-16T06:57:24Z","shared":true,"fromReset":true,"definedStart":false,"rev":2,"steps":[],"groups":[]}
```

`rev` counts changes to the recording. The agent passes the last one it saw on its edits (see step 3).

## 2. The agent acts, you watch

Each step the agent runs is recorded. The first one gets to the starting screen:

```sh
$UI menu '{"path":["File","New order"]}'
$UI type '{"target":{"role":"text","name":"Customer"},"text":"Ada"}'
$UI press '{"keys":"Enter"}'
$UI click '{"target":{"role":"table","row":{"Product":"A4 paper, 500 sheets"},"column":"SKU"}}'
$UI click '{"target":{"role":"button","name":"Add to cart"}}'
$UI type '{"target":{"role":"table","row":{"Product":"A4 paper, 500 sheets"},"column":"Qty"},"text":"3","commit":true}'
```

Each answer carries the step as it is recorded, like this one:

```json
{"passed":true,"action":"menu([File, New order])","message":"","ms":417,"recorded":{"action":"menu","path":["File","New order"]}}
```

Then it checks what you can see on screen, with `expect`: the cart total is the label named Total. 3 packs of A4 paper, 500 sheets at 4.90 is 14.70, and 20% VAT makes 17.64.

```sh
$UI expect '{"target":{"role":"label","name":"Total"},"text":"€17.64"}'
```

A step that fails is not recorded. If an `expect` runs too early, before the quantity is set, the answer is `EXPECTATION: expected text '€17.64' but was '€5.88'` and nothing is recorded. The agent checks only values you can see; they are exact and static, see [Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/).

```sh
$UI recording '{"op":"stop"}'
```

`stop` ends the recording and returns the list (the person's **Done** ends it too). After it no step joins. The steps are numbered 1 to 6 and the check is `6.1`: the first check of step 6. The same numbers appear in the list, the palette, the reports and the file; see [Recorded test format](/swing-ui-agent/reference/recorded-test-format/).

### What the palette shows

Steps appear in the palette's Steps list as the agent records them, without a click from you. This part comes from the palette's code: the commands on this page were run against Order Desk with no person at the screen.

- The agent's rows start with `agent:`, yours do not. A row reads like `6  ●  agent: type(…)`; a check sits under its step as `6.1  ●  check: …`.
- A step the agent inserted or edited and has not replayed yet shows `✎` instead of `●`.
- Each edit by the agent shows for a few seconds as a note on the palette's bar, for example "the agent deleted step 7"; then the bar's text comes back.
- When the agent starts a recording, the palette switches to it at once.

## 3. One list, both of you edit it

While the agent records, and after it stops, you can use the **Steps** list: select a step and delete it, redo from it, or press **Undo**. The agent edits with the `recording` tool. Its edits and yours go into **one recording with one history**.

The agent passes the `rev` of the last list it read on each edit. If you changed the recording since, its edit is refused:

```json
{"errorType":"INPUT_ERROR","reason":"BUSY","message":"BUSY: the recording changed since revision 10 (now 11; last change: the agent deleted step 3): list it again"}
```

Say you delete step 4 in the palette. The agent's next edit gets this refusal, with "the person deleted step 4" in place of "the agent deleted step 3" (here a second caller stood in for the person). The agent runs `recording {"op":"list"}`, reads the new steps and `rev`, and carries on. It cannot overwrite what you did without seeing it.

**Undo** takes back the last edit, whether it was yours or the agent's. The agent's call:

```sh
$UI recording '{"op":"undo"}'
```

It answers with the list and `"did"`, such as `undid the group of steps 4-6 by the agent` after the agent grouped them. When you press **Undo** in the palette, its bar says the same: "undid: group of steps 4-6 by the agent".

## 4. Change a step in one request

You tell the agent: "Delete step 7 and redo from 6 with 4 packs." Suppose the agent had also clicked the Gel pen row as step 7 by mistake. It makes it one request:

```sh
$UI recording '{"op":"delete","n":7,"rev":14}'
$UI recording '{"op":"redo","from":6,"rev":15}'
```

`delete` answers `"did":"deleted step 7"` with the new list. `redo` cuts step 6 and the ones after it, resets the app, replays steps 1 to 5 and records on:

```json
{"passed":true,"ran":5,"total":5,"reset":true,"recording":true,"rev":17,...}
```

(Left out: `list`, the recording's steps.) The agent then does step 6 again with 4 packs and checks the new total, 23.52:

```sh
$UI type '{"target":{"role":"table","row":{"Product":"A4 paper, 500 sheets"},"column":"Qty"},"text":"4","commit":true}'
$UI expect '{"target":{"role":"label","name":"Total"},"text":"€23.52"}'
```

There is no need to reset and start over. Numbers close up after a delete, so the steps are 1 to 6 again.

While a replay or a redo runs, an edit is refused, and so is yours in the palette:

```json
{"errorType":"INPUT_ERROR","reason":"BUSY","message":"BUSY: the agent is replaying the recording (steps 1-6)"}
```

The palette gives its own refusal, "the agent is replaying the recording", for the same case.

## 5. Ask it to name the steps

Say "name the steps". The agent writes a sentence for each step and check with `recording edit`, and a sentence for each stretch of steps with `recording group`. Pass the latest `rev` on every call:

```sh
$UI recording '{"op":"edit","n":1,"say":"Open a new order","rev":38}'
$UI recording '{"op":"edit","n":2,"say":"Enter the customer Ada","rev":39}'
$UI recording '{"op":"edit","n":"6.1","say":"The cart total is 23.52","rev":44}'
$UI recording '{"op":"group","from":1,"to":3,"say":"Start an order for Ada","rev":45}'
$UI recording '{"op":"group","from":4,"to":6,"say":"Put 4 packs of A4 paper in the cart","rev":46}'
```

Each call answers with the new list and `"did"`, such as `set the sentence of step 2` and `grouped steps 1-3`. (Left out: the calls for steps 3 to 6, which look like these.) You see the sentences in the Steps list at once, and you can change them: double click a row, press F2 or Enter. Undo takes one back. Sentences change only how the test reads, not what it does. [Sentences and groups](/swing-ui-agent/guides/sentences/) has more.

## 6. Save it and run it

```sh
$UI export_recorded_test '{"name":"four-packs-of-a4","description":"Starts an order for Ada, puts 4 packs of A4 paper in the cart; the cart total reads 23.52."}'
```

It writes `uitest/four-packs-of-a4.json` (the `savedTo` in the answer) and says `"steps":6,"checks":1`. To keep the recording instead, the agent uses `recording {"op":"export","as":"recorded",…}`; the file then becomes the one the recording came from, so a later export under the same name saves over it (any other existing file is refused: "already exists; choose another name"). To have a Java test, the agent exports it with `export_test`; both are described in [Record a test with the agent](/swing-ui-agent/start/record/) and [Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/).

Then it runs it:

```sh
$UI run_recorded_test '{"name":"four-packs-of-a4"}'
```

```json
{"passed":true,"test":"four-packs-of-a4","ran":6,"total":6,"ms":311,"steps":[{"n":1,"say":"Open a new order","action":"menu([File, New order])","passed":true,"ms":158},...,{"n":"6.1","say":"The cart total is 23.52","action":"expect(label 'Total' text '€23.52')","passed":true,"ms":0}]}
```

(Left out: steps 2 to 6, the file path and the text report.) Where the file lives, how to run it from the palette, Maven or CI: [Run and edit a recorded test](/swing-ui-agent/start/recorded-tests/).

## What's next

- [Record a test by showing it](/swing-ui-agent/start/show/): you record with the palette.
- [Sentences and groups](/swing-ui-agent/guides/sentences/) and [Recorded test format](/swing-ui-agent/reference/recorded-test-format/).
- [Run and edit a recorded test](/swing-ui-agent/start/recorded-tests/).
