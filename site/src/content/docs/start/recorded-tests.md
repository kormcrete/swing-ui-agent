---
title: Run and edit a recorded test
description: Where recorded tests live, how to save one, open it in the palette, run it from the agent, a shell, Maven or CI, and edit it.
sidebar:
  order: 11
---

A recorded test is a JSON file, one step per action, with its checks under the step. It holds exact values that were visible when it was recorded. For when to choose one over a Java test, see [Recorded tests or Java tests](/swing-ui-agent/start/choose-test-kind/). This page is about using one.

## Where they live

- The folder is `uitest` in the app's working directory.
- Change it with the agent option `recorded=<dir>` (the path cannot contain a comma), or `-Dswingui.recordedDir=<dir>`. The option wins.
- In the palette, **Choose…** in the **Export…** and **Load recorded test…** panels picks another folder. The palette remembers the last folder you loaded from or saved to, per app working directory and across restarts (in your Java user preferences, not in the project), unless `recorded=` or `-Dswingui.recordedDir` sets the folder.
- One test, one file: `uitest/<name>.json`. An optional `uitest/gate.txt` says which run in Maven and CI; see [In Maven and CI](#in-maven-and-ci).

## Save one

- **A person:** after **Done**, press **Export…**. The panel opens on **Recorded test**. Give it a name and press **Export**.
- **The agent:** `export_recorded_test {"name":…,"description":…}` writes the steps recorded since the last reset. It refuses a name that already exists, except the file the recording was loaded from.
- **Keep recording after saving:** `recording {"op":"export","as":"recorded",…}` writes the same file and keeps the recording, for the next one.

Here is one from a real recording on Order Desk: open a new order for Ada, put two products in the cart, check the totals. The agent named the steps with `recording edit`, then saved it.

```sh
$UI recording '{"op":"start","reset":true}'
# … the agent acts (menu, type, press, click, type) and adds expect checks …
$UI recording '{"op":"stop"}'
$UI export_recorded_test '{"name":"cart-totals","description":"A cart for Ada Shop Ltd: A4 paper x3 and Gel pen x10, with its line total, subtotal, volume discount, VAT and total"}'
```

That wrote `uitest/cart-totals.json`, in Order Desk's repository. Its first three steps:

<!-- snippet: uitest/cart-totals.json#L1-L40 -->
```json
{
  "version" : 2,
  "name" : "cart-totals",
  "description" : "A cart for Ada Shop Ltd: A4 paper x3 and Gel pen x10, with its line total, subtotal, volume discount, VAT and total",
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
    {
      "action" : "type",
      "say" : "Type “Ada” into Customer",
      "target" : {
        "role" : "text",
        "name" : "Customer"
      },
      "text" : "Ada"
    },
    {
      "action" : "press",
      "say" : "Press Enter to pick Ada Shop Ltd",
      "keys" : "Enter",
      "checks" : [
        {
          "say" : "The New order tab for Ada Shop Ltd is selected",
          "target" : {
            "role" : "tab",
            "name" : "New order · Ada Shop Ltd"
          },
          "state" : [
            "selected"
          ]
        }
      ]
    },
```

The file goes on the same way: 11 steps and 7 checks. The tool writes the file with one field per line. `"start": "reset"` means every run begins with the app reset.

## Open one in the palette

1. Press **Load recorded test…** (the palette is on by default; `palette=off` turns it off). The panel lists the files in the folder with their description and step count. A file that cannot be read is greyed with the reason.
2. Choose one and press **Load** (or double click it). It becomes the recording and the **Steps** list shows it: numbered sentences, checks under their step.
3. Press **▶ Run** to run it from the first step, against the screen as it is after a reset.
4. Pick the speed: **Fast**, **Normal**, **Slow** or **Step by step**. Step by step waits for you at each step; an action and the checks under it count as one.
5. Every step shows a ✓ or ✗ when it ran. They stay until you press **Next**, so you can read them.

The agent cannot run or record while you replay: its calls are refused with BUSY until you finish.

## Edit it

In **Steps**, the same tools as when recording:

- Name a step or a check: double click, **F2** or **Enter**, or **⋯** / right click → **Describe this step…**. See [Sentences and groups](/swing-ui-agent/guides/sentences/).
- **Delete** a step, or **↺ Redo from selected**: go back to that step and record again from there.
- Replay all, or from a step, to see the effect of a change.

To keep the change, press **Export…**. If the name is the test you loaded, the button reads **Save** and writes over it. With any other name it reads **Export** and writes a new file, so the original stays.

## Run it from the agent or a shell

`run_recorded_test {"name":"cart-totals"}` (or `path`, or the JSON inline as `test`) resets the app, runs every step without recording, and stops at the first that fails:

```sh
$UI run_recorded_test '{"name":"cart-totals"}'
{"passed":true,"test":"cart-totals","file":"…/uitest/cart-totals.json","ran":11,"total":11,"ms":1552,
 "steps":[{"n":1,"say":"Open a new order","action":"menu([File, New order])","passed":true,"ms":451}, … ,
          {"n":"9.5","say":"The cart total is €31.32","action":"expect(label 'Total' [tab:New order · Ada Shop Ltd] text '€31.32')","passed":true,"ms":3},
          {"n":11,"say":"Discard the order","action":"answer(Discard)","passed":true,"ms":121}],
 "report":"Recorded test 'cart-totals' passed:\n  driver: …\n  [1] PASS Open a new order  ms=451 … menu([File, New order]) …"}
```

- `ran` and `total` count steps. A check is not counted; it is listed with the label `9.5` (step 9, check 5). The same numbers are in the palette and the file.
- Left out of the quote: the per-step timings and the `driver:` line of the report.
- The CLI exits `0` when it passed. `4` means the recorded test ran and failed; the report is on stderr. See [Exit codes](/swing-ui-agent/reference/cli/#exit-codes).

A failing one, where the last check expected `€31.33`:

```text
Recorded test 'cart-totals-wrong' failed:
  [9] PASS Order 10 gel pens                            ms=98 …
    [9.4] PASS The VAT is €5.22                             ms=0 …
    [9.5] FAIL The cart total is €31.33                     ms=10051 …
        expect(label 'Total' [tab:New order · Ada Shop Ltd] text '€31.33')
      EXPECTATION: expected text '€31.33' but was '€31.32'
      target label 'Total' [tab:New order · Ada Shop Ltd] matched 1:
        - label 'Total' in main [tab:New order · Ada Shop Ltd]
      tree of tab 'New order · Ada Shop Ltd' (depth 4, compact, 19 nodes):
        …
        label 'VAT (20%)' = "€5.22"
        label 'Total' = "€31.32"
        button 'Checkout…'
      screenshot: …/target/ui-screenshots/failures/cart-totals-wrong-step9.5.png
```

The failing step carries what its locators match now, and a PNG of the window (`evidence`, default `target/ui-screenshots/failures`). Left out: steps 1 to 8, most of the tree and the `driver:` line.

## In Maven and CI

The whole test class:

```java
@UiTest
class RecordedUiIT {

    @TestFactory
    Stream<DynamicTest> recorded(Client ui) {
        return RecordedTests.of(ui);
    }
}
```

It is named `*UiIT`, so the same failsafe run as the [Java tests](/swing-ui-agent/start/run/) and the same [CI job](/swing-ui-agent/start/ci/) pick it up. No other setup. Each recorded test is one JUnit test, named after its file:

```text
[recorded-test] cart-totals passed: 11 steps, 1552 ms
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

- **Which ones run.** `-Drecorded=<name>` runs just that one. Otherwise the folder's `gate.txt` lists names, one per line, in the order to run (`#` starts a comment). Without `gate.txt`, all run, sorted by name.
- **A run that selects nothing fails.** So does a missing folder or a name that is not there, with the reason in the message.
- **Each begins with a reset**, like every test.
- **Where the folder is:** `RecordedTests.of(ui)` uses `-Dswingui.recordedDir`, else `uitest` in the test module. `RecordedTests.of(ui, folder)` takes another.
- **A failure** fails its test with the report. The report and the picture of the failing step go to `target/failsafe-reports/recorded-test-failures/`: `<name>.txt` and `<name>-step5.1.png`.
- **In CI, turn the palette off** with `palette=off`. Nobody is there to use it, and a run holds the app's lease, which would hide it anyway.

## What's next

- [Record a test with the agent](/swing-ui-agent/start/record/) and [by showing it](/swing-ui-agent/start/show/).
- [Run in CI](/swing-ui-agent/start/ci/).
