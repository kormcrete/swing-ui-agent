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
- In the palette, **Choose…** in the **Export…** and **Load recorded test…** panels picks another folder. A folder you loaded a test from is remembered for the next save.
- One test, one file: `uitest/<name>.json`. An optional `uitest/gate.txt` says which run in Maven and CI; see [In Maven and CI](#in-maven-and-ci).

## Save one

- **A person:** after **Done**, press **Export…**. The panel opens on **Recorded test**. Give it a name and press **Export**.
- **The agent:** `export_recorded_test {"name":…,"description":…}` writes the steps recorded since the last reset. It refuses a name that already exists, except the file the recording was loaded from.
- **Keep recording after saving:** `recording {"op":"export","as":"recorded",…}` writes the same file and keeps the recording, for the next one.

Here is a small one exported from a real recording on Order Desk: open a new order for Ada, add Gel pen, check the cart total. Gel pen, black costs 1.20, plus 20% VAT.

```sh
$UI recording '{"op":"start","reset":true}'
# … the agent acts (menu, type, press, click, click) and adds an expect check …
$UI recording '{"op":"stop"}'
$UI export_recorded_test '{"name":"gel-pen-total","description":"Starts an order for Ada and adds Gel pen, black; the cart total reads 1.44."}'
```

That wrote `uitest/gel-pen-total.json`:

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

The tool writes the file with one field per line; here short objects are joined to one line, nothing else is changed. `"start": "reset"` means every run begins with the app reset.

## Open one in the palette

1. Press **Load recorded test…** (the palette is on by default; `palette=off` turns it off). The panel lists the files in the folder with their description and step count. A file that cannot be read is greyed with the reason.
2. Choose one and press **Load** (or double click it). It becomes the recording and the **Steps** list shows it: numbered sentences, checks under their step.
3. Press **▶ Run** to run it from the first step, against the screen as it is after a reset.
4. Pick the speed: **Fast**, **Normal**, **Slow** or **Step by step**. Step by step waits for you at each step; an action and the checks under it count as one.
5. Every step shows a ✓ or ✗ when it ran. They stay until you press **Next**, so you can read them.

The agent cannot run or record while you replay: its calls are refused with BUSY until you finish.

## Edit it

In **Steps**, the same tools as when recording:

- Name a step or a check: double click, **F2** or **Enter**, or **⋯** / right click → **Describe this step…**.
- **Delete** a step, or **↺ Redo from selected**: go back to that step and record again from there.
- Replay all, or from a step, to see the effect of a change.

To keep the change, press **Export…**. If the name is the test you loaded, the button reads **Save** and writes over it. With any other name it reads **Export** and writes a new file, so the original stays.

## Run it from the agent or a shell

`run_recorded_test {"name":"gel-pen-total"}` (or `path`, or the JSON inline as `test`) resets the app, runs every step without recording, and stops at the first that fails:

```sh
$UI run_recorded_test '{"name":"gel-pen-total"}'
{"passed":true,"test":"gel-pen-total","file":"…/uitest/gel-pen-total.json","ran":5,"total":5,"ms":478,
 "steps":[{"n":1,"action":"menu([File, New order])","passed":true,"ms":200}, … ,
          {"n":"5.1","action":"expect(label 'Total' text '€1.44')","passed":true,"ms":1}],
 "report":"Recorded test 'gel-pen-total' passed:\n  [1] PASS menu([File, New order]) …"}
```

- `ran` and `total` count steps. A check is not counted; it is listed with the label `5.1` (step 5, check 1). The same numbers are in the palette and the file.
- Left out of the quote: the per-step timings and the `driver:` line of the report.
- The CLI exits `0` when it passed. `4` means the recorded test ran and failed; the report is on stderr. See [Exit codes](/swing-ui-agent/reference/cli/#exit-codes).

A failing one, where the check expected `€1.45`:

```text
Recorded test 'gel-pen-wrong' failed:
  [5] PASS click(button 'Add to cart') …
    [5.1] FAIL expect(label 'Total' text '€1.45') …
      EXPECTATION: expected text '€1.45' but was '€1.44'
      target label 'Total' matched 1:
        - label 'Total' in main [tab:New order · Ada Shop Ltd]
      tree of the matched element (depth 2, compact, 2 nodes):
        label 'Total' = "€1.44"
      screenshot: …/target/ui-screenshots/failures/gel-pen-wrong-step5.1.png
```

The failing step carries what its locators match now, and a PNG of the window (`evidence`, default `target/ui-screenshots/failures`). Left out: steps 1 to 4 and the `driver:` line.

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
[recorded-test] gel-pen-total passed: 5 steps, 379 ms
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
