---
title: Run journal
description: "What a UI test run leaves in target/ui-runs: the run folder, run.json, one journal per test, checkpoints and their sidecars, and the settings."
sidebar:
  order: 5
---

Every `@UiTest` leaves a record of what it did in its test module's `target/ui-runs`. A Java test leaves one, and so does a recorded test run through `RecordedTests`. The record is a journal of each step, check and read, written as the test runs, plus the pictures the test names. You register nothing: it is on whenever the library is on the test class path.

The folders are for reading later, by a person or an agent: what a test did, step by step, in fields and in an English sentence. A test, its sections and its checkpoints keep the names the test's author gave them, so the same test can be lined up across releases: unchanged, changed at this step, or failed at this step.

Each file states its format: `run.json`, every sidecar and every journal's `test` line carry `"format": 1`. That number is the contract this page describes. A reader should check it, and ignore fields it does not know.

A [sample run folder](https://github.com/kormcrete/swing-ui-agent/tree/main/src/test/resources/run-journal-sample) from a real run is in the repository, for use as a fixture.

## What a run leaves

```text
target/ui-runs/
  latest.txt                      the run report of the latest test run with a UI test
  latest-run.txt                  the id of the latest run folder, one line
  UIT-20261023-081204-a3f9/       one run folder per test JVM
    run.json                      the run: when, library, Java, OS, the agents and apps it drove, counts
    report.txt                    the run report's lines for this run, secrets masked
    steps/
      PlaceOrderUiIT#placesAnOrderForAda.jsonl
      RecordedUiIT#recorded[place-order].jsonl
    checkpoints/
      PlaceOrderUiIT#placesAnOrderForAda/
        cart-totals.png
        cart-totals.json
```

- `target` is the test module's, found from the test class, whatever the working directory (an IDE may run tests from the project root).
- A test's id is `<Class>#<method>`. `<Class>` is the class's binary name after its last `.`, so a nested class is `Outer$Inner`. A repeated or parameterized test adds the invocation number: `OrdersUiIT#totals[2]`. `latest.txt` uses the same ids, `[n]` included.
- Each test that writes something gets one journal file: `steps/<test id>.jsonl`. Characters a file name cannot hold on some OS (`<>:"/\|?*` and control characters) become `_` in the file name; the `test` field keeps the real id.
- The same test id twice in one run gets a file of its own, never appended to the first: `<test id>~2.jsonl`, with checkpoints in `<test id>~2/`. This happens when one JVM runs a class twice, or runs two classes with the same simple name from different packages.

## Three ids

| Id | Looks like | One per | Where |
| --- | --- | --- | --- |
| Run id | `UIT-20261023-081204-a3f9`, or what `-Dswingui.runId` names | test JVM | The run folder's name, `runId` in `run.json` and in each `test` line, ` run=<id>` in `latest.txt`, `latest-run.txt`. |
| `ui.runId()` | `UIT-20261023-081205-7c1e` | client of a test | Not in the journal. A name for what the test creates. |
| `ui.run().id()` | `k3v9q2xa` (eight letters and digits) | test method, the same for all its clients | What a recorded test's `${run.id}` gives. In the journal only where a step used it: a typed value, a recorded test's `resolved`. |

A run id is `UIT-` and the local date and time the test JVM first needed one (`yyyyMMdd-HHmmss`), then four random hex digits.

## run.json

Written when the run folder is made and rewritten whole as the run goes on: a new agent seen, the end of a test run.

```json
{
  "format" : 1,
  "runId" : "UIT-20261023-081204-a3f9",
  "started" : "2026-10-23T08:12:04.512Z",
  "finished" : "2026-10-23T08:13:47.928Z",
  "library" : "0.1.0-SNAPSHOT",
  "java" : "1.8.0_291",
  "os" : "Mac OS X 15.4 aarch64",
  "app" : {
    "version" : "0.1.3"
  },
  "clients" : [ {
    "agent" : "127.0.0.1:8770",
    "agentVersion" : "0.1.0-SNAPSHOT",
    "app" : {
      "name" : "Order Desk",
      "version" : "0.1.3"
    },
    "scale" : 2.0,
    "laf" : "com.formdev.flatlaf.FlatLightLaf",
    "window" : {
      "w" : 1280,
      "h" : 800
    }
  } ],
  "counts" : {
    "passed" : 11,
    "failed" : 1,
    "skipped" : 0,
    "aborted" : 0
  }
}
```

| Field | What it is |
| --- | --- |
| `format` | `1`. |
| `runId` | The run folder's name. |
| `started` | When the run folder was made. |
| `finished` | When the latest test run in this JVM ended. Missing until the first one ends (also when the JVM is killed before). |
| `library` | The swing-ui-agent version on the test class path. |
| `java`, `os` | The test JVM's Java version, and its OS name, version and architecture. |
| `app.version` | Only when the run names it: `UiRunJournal.appVersion(...)`, else `-Dswingui.appVersion`. |
| `clients` | Each agent the run drove, the first time it was seen. `agent` is its address. `alias` is the login alias of the test that first used it, when the test had one. The rest is what the agent said of itself and its app: `agentVersion`, `app.name`, `app.version`, `scale` (the main window's screen scale, `2.0` on a Retina display), `laf` (the look and feel class) and `window` (the main window's size). Each is present only when known: an older agent does not report them. `app.version` is the run's version when it names one, else the version the app reports through its plugin. |
| `counts` | The passed, failed, skipped and aborted tests of the latest test run in this JVM that had a UI test. A build's test JVM usually runs one. |

## report.txt

The [run report](/swing-ui-agent/start/run/#a-run-report-for-scripts)'s lines for this run, in `latest.txt`'s line format, appended for each test run of this JVM:

```text
STARTED 2026-10-23T08:12:04.520Z tests=2 run=UIT-20261023-081204-a3f9
PASS PlaceOrderUiIT#placesAnOrderForAda 4255ms
FAIL OrdersUiIT#totals 10431ms — Expected label 'Total' to have text '€7.32' — evidence /home/ci/order-desk/target/ui-failures/OrdersUiIT/totals
DONE passed=1 failed=1 skipped=0 aborted=0
```

`report.txt` masks every secret a journal of the same JVM was given (see [Secrets](#secrets)). `latest.txt` and the console do not: a failure message that quotes a secret shows it there. The failure evidence under `target/ui-failures` is not masked either.

## Journal lines

A journal is one JSON object per line, appended and flushed as each step ends. A test that fails stops at the step that failed, and the `end` line follows. A line holds these fields:

| Field | What it is |
| --- | --- |
| `seq` | 1, 2, 3, … in the file, with no gaps. |
| `t` | When the step started, in UTC with milliseconds: `2026-10-23T08:12:04.563Z`. |
| `kind` | `test`, `section`, `action`, `check`, `read`, `checkpoint` or `end`. |
| `client` | The client's number (from 1), only in a test with more than one client. |
| `verb` | The step's name, as a recorded test names it: `type`, `click`, `menu`, `expect`, … For a read, the Java method's name: `shownText`, `rowCount`, … |
| `target` | Where it acted, in the words failure evidence uses: `table 'Lines' [tab:Orders] row={Number=ORD-1640} column=Qty`. |
| `locator` | The same place as a recorded test's `target` object, to tell the place from per-run values. |
| `value` | What it typed, chose or read. A read keeps its JSON type: a count is a number, `rows` a list. |
| `expected` | A check's expectation: `{"text":"€7.32"}`, `{"cell":{…}}`, `{"what":"…"}` for an `eventually`. |
| `step` | The whole step, as a recorded test holds it. |
| `outcome` | `ok` or `failed` on `action`, `check`, `read` and `checkpoint` lines; `passed`, `failed` or `aborted` on the `end` line. |
| `message`, `reason` | Only when it failed: the message, and the reason (`EXPECTATION`, `NOT_FOUND`, `NOT_ACTIONABLE`, …) when the driver gave one. |
| `ms` | How long it took. On the `end` line, the whole test. |
| `text` | One English sentence for the line. |

Java methods journal under the step names recorded tests use: `fill("…")` is a `type` with `replace` and `commit`, `open()` is a `click` with `count` 2, `rightClick(path)` is a `contextMenu`. An `expect` is a `check`, a recorded test's `remember` is a `read`, and every other step is an `action`.

### test

The first line of every file: the test's id, the run id, and its clients with what each agent said. Each client is listed as in `run.json`, plus its number.

```json
{"seq":1,"t":"2026-10-23T08:12:04.540Z","kind":"test","format":1,"test":"PlaceOrderUiIT#placesAnOrderForAda","runId":"UIT-20261023-081204-a3f9","clients":[{"client":1,"agent":"127.0.0.1:8770","agentVersion":"0.1.0-SNAPSHOT","app":{"name":"Order Desk","version":"0.1.3"},"scale":2.0,"laf":"com.formdev.flatlaf.FlatLightLaf","window":{"w":1280,"h":800}}]}
```

### section

`ui.step("Place the order")` starts a part of the test, in the author's words. The lines after it belong to it, up to the next section. Line breaks become spaces; nothing else in the sentence changes.

```json
{"seq":2,"t":"2026-10-23T08:12:04.559Z","kind":"section","text":"Place the order"}
```

### action

```json
{"seq":4,"t":"2026-10-23T08:12:04.612Z","kind":"action","verb":"type","target":"table 'Lines' [tab:Orders] row={Number=ORD-1640} column=Qty","locator":{"role":"table","name":"Lines","scope":["tab:Orders"],"row":{"Number":"ORD-1640"},"column":"Qty"},"value":"16","step":{"action":"type","target":{"role":"table","name":"Lines","scope":["tab:Orders"],"row":{"Number":"ORD-1640"},"column":"Qty"},"text":"16"},"outcome":"ok","ms":412,"text":"Type 16 into Qty of row ORD-1640 in Lines (tab Orders)"}
```

App steps are actions too: `navigate`, `close`, `loginAs` and `relogin`. A login's `step` names the alias (`as`) or the user (`user`), never the password. The `step` of `loginAs(user, password)` and of `relogin()` is not a step a recorded test can replay.

### check

```json
{"seq":6,"t":"2026-10-23T08:12:05.110Z","kind":"check","verb":"expect","target":"label 'Total'","locator":{"role":"label","name":"Total"},"expected":{"text":"€7.32"},"step":{"action":"expect","target":{"role":"label","name":"Total"},"text":"€7.32"},"outcome":"ok","ms":31,"text":"Check that Total shows €7.32"}
```

`UiAssertions.eventually(what, within, read, ok)` writes one check line, not a read line per poll: verb `eventually`, the author's `what` as `expected.what` and in the sentence, and the target and value of its last read only. An `eventually` inside another polls as part of the outer one, and only the outer one writes a line.

```json
{"seq":8,"t":"2026-10-23T08:12:05.140Z","kind":"check","verb":"eventually","target":"label 'Total'","locator":{"role":"label","name":"Total"},"expected":{"what":"the Total shows €7.32"},"value":"€7.32","outcome":"ok","ms":161,"text":"Check that the Total shows €7.32"}
```

### read

Each public read of a `Target` or `Client` writes one line, under its own name, with the value it returned: `text`, `shownText`, `selectedText`, `value`, `tooltip`, `columns`, `rows`, `rowCount`, `count`, `values`, `texts`, `selectedRows`, `items`, `expandedItems`, `states`, `bounds`, `presence`, `exists`, `menuItems` (with the menu as `path` instead of a target), `clipboardText`, `fileChooser`, `modalShowing` and `describeModal`. A read that fails is a line with `outcome` `failed`, and the read still throws.

```json
{"seq":7,"t":"2026-10-23T08:12:05.120Z","kind":"read","verb":"rowCount","target":"table 'Orders'","locator":{"role":"table","name":"Orders"},"value":17,"outcome":"ok","ms":147,"text":"Count the rows of Orders: 17"}
```

### checkpoint

```json
{"seq":9,"t":"2026-10-23T08:12:05.302Z","kind":"checkpoint","name":"cart-totals","target":"section 'Cart'","locator":{"role":"section","name":"Cart"},"png":"checkpoints/PlaceOrderUiIT#placesAnOrderForAda/cart-totals.png","json":"checkpoints/PlaceOrderUiIT#placesAnOrderForAda/cart-totals.json","masks":1,"outcome":"ok","ms":120,"text":"Checkpoint cart-totals of section Cart, masking Date"}
```

`png` and `json` are relative to the run folder, with `/` on every OS. `masks` is how many masks the test gave. A checkpoint that failed has no `png` or `json`, and has the failure's `message`. See [Checkpoints](#checkpoints).

### end

The last line: how the test ended. `outcome` is `passed`, `failed` or `aborted`. `message` is the failure's first line. For a Java test, `evidence` is the absolute path of its folder under `target/ui-failures`. For a recorded test, it is the absolute path of the failure report under `target/failsafe-reports/recorded-test-failures`.

```json
{"seq":11,"t":"2026-10-23T08:12:15.330Z","kind":"end","outcome":"failed","ms":10790,"message":"Expected label 'Total' to have text '€7.32'","evidence":"/home/ci/order-desk/target/ui-failures/PlaceOrderUiIT/placesAnOrderForAda"}
```

Before it, a failing test's journal ends at the step that failed:

```json
{"seq":10,"t":"2026-10-23T08:12:05.318Z","kind":"check","verb":"expect","target":"label 'Total'","locator":{"role":"label","name":"Total"},"expected":{"text":"€7.32"},"step":{"action":"expect","target":{"role":"label","name":"Total"},"text":"€7.32"},"outcome":"failed","message":"expected text '€7.32' but was '€5.88'","reason":"EXPECTATION","ms":10012,"text":"Check that Total shows €7.32"}
```

Two things to know about the `end` line:

- A test that fails before its journal exists leaves no file. This happens when no agent answers, the app's plugin is not ready, or a run guard refuses. A test whose reset fails leaves just its `test` and `end` lines.
- The `end` line is written first when the test finishes. Ending the test's runs and giving back its leases come after it. A failure there can still fail a test whose `end` line says `passed`.

### The sentence

`text` is one English line made from the verb, the place and the value or expectation: "Type Ada Shop Ltd into Customer", "Click Place order", "Choose File > New order in the menu bar", "Check that Total shows €7.32", "Read what Total shows: €7.32". A section's `text` is the author's sentence. In a recorded test, a step's own `say` is its `text`. A `do` step without a `say` gets the sentence its plugin gives, as the palette shows it. There is no translation: `text` is English, and the fields carry the same facts.

### Sizes

One line stays small, whatever the test reads or types:

- A `value` longer than 2,000 characters is cut to 2,000 and the line gets `"capped": true`. For a list or a map, that is its JSON text: a capped list is a string.
- Each text inside `step`, `locator` and `expected` is cut at 2,000 characters. How many entries they hold is not limited.
- A sentence holds at most 60 characters of a value, then `…`.

### Secrets

These values are written as `***` in the `target`, `locator`, `value`, `expected`, `step`, `message`, `text` and `resolved` of every line written after the value is seen, and in the sidecars' targets:

- a value read with `ui.secret(alias, name)`;
- text typed into a `password` target;
- the password given to `loginAs(user, password)`.

The run's `report.txt` masks them too (see [report.txt](#reporttxt)).

### Times

`t` is when a line's step started, `ms` how long it took. For a recorded test, the lines are written when it ends, from the agent's reply: each `t` is the recorded test's start plus the `ms` of the steps before it.

## What is not in the journal

- Plain JUnit or AssertJ assertions on values a test read: the read is a line, the assertion is not. A failing one still shows in the `end` line.
- `tree(…)` dumps, `screenshot()` and `saveScreenshot(…)` (pictures for people; use a [checkpoint](#checkpoints) for a picture in the run folder), plugin hook calls, and the reads of an `Outcome`.
- The reset before each test: the journal starts with the first step after it.

## Checkpoints

A checkpoint names a moment of the test and keeps a picture of it, to compare with the same checkpoint of another run:

```java
ui.checkpoint("order-form");                                    // the main window
ui.checkpoint("order-form", ui.label("Order date"));            // with a mask
ui.tab("Orders").table().row("Number", "ORD-1640").checkpoint("order-row");
ui.section("Cart").checkpoint("cart-totals", ui.label("Date"));
```

- The name is the author's and says what the picture shows: `A-Z a-z 0-9 . _ -` only (not `.` or `..`), once per test. Names that differ only in case are refused too, as a disk may not tell them apart. Anything else fails the test at once.
- Use few: one, at most three per test.
- A checkpoint the test asks for that cannot be taken or written fails the test. Examples: a target scrolled out of view or with no size (NOT_ACTIONABLE, "scroll it into view first"), a mask in another window or outside the picture (BAD_INPUT), a file that cannot be written. An agent older than the library fails it as CAPABILITY_GAP.
- Checkpoints are written even with the journal off, into a run folder made for them.

### The picture

`checkpoints/<test id>/<name>.png` shows exactly what the target names:

- a window shows its content, with no title bar or border;
- a table cell, row or tree or list item shows just that part, as far as it is in view;
- a table column shows its header cell and its cells in view;
- any other target shows the part of the component on screen.

The picture is painted at scale 1 and never scaled down. Two runs of the same screen must give the same pixels, so before the paint the agent does this:

1. It waits for the app's pending events.
2. It moves the pointer off the window, as a person moving the mouse away would. This clears a rollover the test's own hover or click left.
3. It waits again, so layout settles.
4. It paints the app's components offscreen. Nothing is grabbed from the screen, and the palette is left out.

Two things a checkpoint does to the app, both on purpose:

- **The pointer:** the app gets the mouse-exit events a person moving the mouse away would cause. The real pointer does not move. So the `screenshot` tool with `"checkpoint": true` needs the lease like an action, though the tool is still marked read-only.
- **The caret:** a text caret that is on would make two runs differ. It is turned off for the one paint, and back on in the same event-thread task, so the app never sees it.

### The sidecar

`checkpoints/<test id>/<name>.json`:

```json
{
  "format" : 1,
  "name" : "cart-totals",
  "test" : "PlaceOrderUiIT#placesAnOrderForAda",
  "target" : "section 'Cart'",
  "locator" : {
    "role" : "section",
    "name" : "Cart"
  },
  "width" : 412,
  "height" : 180,
  "masks" : [ {
    "x" : 12,
    "y" : 40,
    "w" : 90,
    "h" : 18,
    "target" : "label 'Date'",
    "locator" : {
      "role" : "label",
      "name" : "Date"
    },
    "clipped" : false
  } ]
}
```

- `width` and `height` are the picture's size.
- Each mask is a rectangle to leave out when comparing: an order date, an order number. Nothing is painted over it. Its `x`, `y`, `w` and `h` are relative to the picture, so a cell checkpoint's masks are relative to the cell.
- `clipped` is true when part of the masked element is not in the picture, cut off by the picture's edge or by the element's own scroll pane. The rectangle is the part that is in the picture.
- `masks` is always a list, `[ ]` when there are none.

## Recorded tests

A recorded test run from JUnit through `RecordedTests.of(ui)` writes its own journal: `steps/<Class>#recorded[<file>].jsonl`, where `<file>` is the file's name without `.json`, for example `RecordedUiIT#recorded[place-order].jsonl`. The factory method's own journal is dropped.

- It is written when the recorded test ends, from the agent's reply: one line per step and check that ran, in order, then the `end` line.
- `step` is the step as it ran: a link such as `${copy.rows.Orders.added[0].Number}` is replaced by the value it stood for. `resolved` lists each link as written with that value.
- A step's `say` is its `text`. A group's `say` is a `section` line before its first step.
- The steps a plugin's `do` step ran follow its line, each with `"parent"`: the `seq` of the `do` step's line.
- A recorded test has no checkpoints.

## Run folders over time

- **One per test JVM.** All UI tests of a JVM, every launch included, write into one run folder.
- **Kept ten deep.** When a run folder is made, the newest ten by `started` stay, the new one among them, and older ones are deleted. Only folders with a format 1 `run.json` whose `runId` is the folder's name count. A folder is never deleted while it is the current run, or while a file in it was changed in the last ten minutes: another JVM of the same build may still write there.
- **Forks.** Each test JVM has its own run id. When the folder name is taken (two forks given the same `-Dswingui.runId`, or a name used before), the run gets `<id>-2`, then `-3`, and so on: two runs never mix.

## Settings

Each is a JUnit configuration parameter, which JUnit also reads from the system property of the same name: `-D…`, `junit-platform.properties` or a suite's `@ConfigurationParameter`. They are read once per JVM, the first time a run folder is needed; a change later in the same JVM is not seen.

| Setting | Default | What it does |
| --- | --- | --- |
| `swingui.journal` | on | `off` writes no steps journal, no `report.txt` and no ` run=` in `latest.txt`. Checkpoints are still written, into a run folder made when the first one needs it. |
| `swingui.journal.keep` | `10` | How many run folders to keep; `0` keeps all. Anything else that is not a whole number of 0 or more warns once and keeps 10. |
| `swingui.runId` | the JVM's run id | The run folder's name, e.g. a CI build number. `A-Z a-z 0-9 . _ -` only; anything else warns once and uses the JVM's run id. |
| `swingui.appVersion` | none | The app version this run tests, into `run.json` and each client's `app.version`. |

From test code, `UiRunJournal.appVersion(OrderDesk.VERSION)` in a `@BeforeAll` or a static initializer names the app version. It wins over `swingui.appVersion` and over the version the app reports, and rewrites a `run.json` already written.

## When writing fails

The journal never fails or slows a passing test. When a journal file cannot be written (a read-only folder, a file in the way), the JVM prints one line, the first time anything of the journal fails:

```text
[ui-warning] run journal off for this run: java.nio.file.AccessDeniedException: …
```

The steps journal is then off for the rest of that JVM's run, and nothing is tried again. Checkpoints still try, and fail their test when they cannot be written.

## What's next

- [The sample run folder](https://github.com/kormcrete/swing-ui-agent/tree/main/src/test/resources/run-journal-sample), from a real run.
- [Run the tests](/swing-ui-agent/start/run/) and [Screenshots](/swing-ui-agent/guides/screenshots/#checkpoints).
- [Sentences and groups](/swing-ui-agent/guides/sentences/) for the `say` of recorded steps and `ui.step` in exported Java tests.
