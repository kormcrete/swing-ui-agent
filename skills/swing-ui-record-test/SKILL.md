---
name: swing-ui-record-test
description: Records a UI test by driving a live Swing app through swing-ui-agent and saves it as a recorded test or a Java @UiTest class. Use when asked to write, record, name or describe a UI test.
---

# Record a UI test

You drive the real app, swing-ui-agent records each step, and you save the steps as a test: a recorded test file
(`export_recorded_test`: JSON that runs without compiling) or a Java `@UiTest` class (`export_test`). The user says
what the test must prove and which checks matter; you choose the steps. Ask when that is unclear; never invent
assertions. A human reviews and runs the test.

## Your project's part

The project's instructions (its `CLAUDE.md`, `AGENTS.md` or the like) name these; they win over the defaults here.

- **Agent command**: how to call the CLI, with the agent jar's path and the app's port. Default
  `java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar` on port 8765 (`--port N` before the tool for another one). With
  several apps, the project names each one's port.
- **Test kind**: which kind to write when the user does not say. Default: a recorded test (below).
- **Running tests**: how to run one Java test and the recorded tests (a run configuration, a build command, a
  script). Default `mvn -Dtest=<Class> verify` (failsafe: `-Dit.test=<Class>`).
- **Starting the app**: how to start or restart it, and where its log is. Default: do not; ask the user.
- **Data**: which app, database or environment the driven app may change, and which data a test may rely on.
  Default: the test creates what it checks; ask before a step that deletes, sends or changes data it did not create.

## Which kind?

- **Recorded test** (the default, unless the project's test kind says otherwise) when the person shows or
  describes behaviour that is visible on screen: exact values, the way a tester or user would check them. It is for
  people showing the app, mostly not technical.
- **Java `@UiTest`** when they ask for something dynamic (a computed or app-made value), or a technical check such
  as a database. It is for engineers working with you.
- The two do not mix; a recorded test is not a base for a Java test. Data is the user's job: the test creates the
  data it checks or uses data the user knows exists. More: https://kormcrete.github.io/swing-ui-agent/start/choose-test-kind/
  and https://kormcrete.github.io/swing-ui-agent/start/recorded-tests/

## Setup

The app runs with `-javaagent:swing-ui-agent-<version>-agent.jar`. Every tool is one shell call; the same tools are
served over MCP at `http://127.0.0.1:<port>/mcp`:

```sh
UI="java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar"   # or the project's agent command
$UI session                 # agent up? which plugins, in which state
$UI tools                   # every tool; `$UI tools expect` prints one tool's schema
```

Exit codes: 0 ok, 1 tool error (message on stderr), 2 usage, 3 no agent reachable (the app is not running with the
agent: start it the project's way if it says how, else stop and say so). A step that ran and failed exits 0 with
`"passed":false`: always read `passed`.

An answering `session` means the agent is up, not that the app is ready. With a plugin, wait for its `state`
`ready`; without one, `expect` something the app enables once it is ready (a toolbar button `!disabled`) first.

`BUSY: leased by <owner> since ...` means a test run holds the app. Wait for it. If that run is dead, retry once the
agent has been idle 15 s, or take the lease: `$UI session '{"op":"lease","owner":"recorder"}'`, pass
`--lease <token>` before the tool name on every call, and `session '{"op":"release","token":"<token>"}'` at the end.

## How recording works

- A test is a list of steps. A step is one action and the checks after it: steps are numbered 1..n, and `7.2` is
  the second check of step 7; checks before any action form step 1. Every number you see (in `recording list`, a
  replay, a run report, a refusal) is a step number or a check label.
- A recording starts at a reset (step 2 below). Every successful step is recorded, mistakes included: a wrong
  click followed by Escape is two steps. A `do` step (below) has no Java form unless its plugin gave it one: it is
  recorded all the same, and only the Java export refuses it. Reads never are: `snapshot`, `read`, `screenshot`,
  `describe_ui`, `read_clipboard`, `read_file_chooser`.
- A test replays from the state right after a reset. Without an app plugin a reset only closes modal dialogs, so the
  app may be on ANY screen: the recording must BEGIN by reaching its own starting screen with `menu` or `press`. The
  exports refuse a recording that did not begin at a reset, and answer with a `startWarning` when one without a
  plugin begins otherwise.
- `navigate`, `close` and `loginAs` need an app plugin (else CAPABILITY_GAP). With one, its reset brings the app to
  a defined start (tabs closed, logged in); `{"op":"reset","alias":"clerk"}` picks the user from
  `.swing-ui-agent/ui-users.properties`.

## The app's own steps (when a plugin offers a vocabulary)

A plugin may name the app's steps ("Add {product} to the cart"). At a recording start, call
`$UI vocabulary '{"op":"list"}'`: an empty list (with a hint) means there are none, and you use the raw verbs.

- **Map the request to a step and concrete values.** `$UI vocabulary '{"op":"describe","step":"order-desk:addToCart"}'`
  shows its params, with `choices` for the values that fit in the app right now. Run it as one step:
  `$UI do_action '{"action":{"action":"do","step":"addToCart","params":{"product":"Gel pen, black"}}}'`; in a
  `batch` it is `{"verb":"do","step":"addToCart","params":{"product":"Gel pen, black"}}`.
  Values are always concrete: no placeholders, nothing carried from one step to the next.
- **Resolve loose values against the choices.** "the gel pens" is `Gel pen, black` when that is the only choice
  that fits. When two fit, ask the person; do not pick. A call may pass a value that is not among the choices, but
  say so.
- **When no step fits, use the raw verbs.** Never stretch a step to do something else.
- **Say which step you used** in your report, as the sentence with its values ("Add 10 × Gel pen, black to the
  cart"). Steps are named `<plugin>:<id>` in the recorded test, even with one plugin.
- Raw steps that match a step fold into it as they are recorded (`recording list` shows `folded` and `raw`); a check,
  a `do` step, a group or sentence, or a change of author stops a fold. `fold {from,to}`, `unfold {n}` (the steps stay
  raw afterwards) and `keepRaw {from,to}` change it. A check step (`kind: check`) never folds by itself: run it
  with `do` where you would use `expect`.
- A recording that begins with a `do` step is a defined start when its steps open with a menu, a key press or
  `navigate`; otherwise start with one of those. A vocabulary-only plugin changes no reset, and `login`/`navigate`
  still need an app plugin.

## Steps

1. **Explore with reads first.** Find the route and every target before recording: scoped `snapshot`, `read`
   (see the swing-ui-targeting skill).
2. **Start at a reset:** `$UI recording '{"op":"start","reset":true}'`. (`session '{"op":"reset"}'` starts a
   recording too, but the palette does not show it.)
3. **Reach the start screen** as the first step: `$UI menu '{"path":["File","New order"]}'`.
4. **Act.** Read each result: `passed`, `message`, `recorded` (the step as it will be saved), `notRecorded`
   (it ran but has no Java form, and why), `pending` (a dialog waits: `answer` it). Once the next steps are
   certain, send them as one `batch`; it stops at the first step that does not pass:
   ```sh
   $UI batch '{"steps":[
     {"verb":"type","target":{"role":"text","name":"Customer"},"text":"Ada"},
     {"verb":"press","keys":"Enter"}]}'
   ```
5. **Check what is visible with `expect`**, so the check is recorded. It retries until its timeout (default 10 s).
   Give exactly one of `state`, `text`, `value`, `tooltip`, `rows`, `cell`, `selected`, `shown`, `absent`
   (`"shown":true` = it is there and shown; `"absent":true` = it is gone, e.g. a closed tab or dialog, with a locator, not a ref):
   ```sh
   $UI expect '{"target":{"role":"table"},"cell":{"row":{"Product":"A4 paper, 500 sheets"},"column":"Qty","expected":"3"}}'
   $UI expect '{"target":{"role":"label","name":"Total"},"text":"€31.32"}'
   $UI expect '{"target":{"role":"button","name":"Checkout…"},"state":["!disabled"]}'
   ```
   Check a value shown next to a caption (a total, a status) by that caption: `label 'Total'` + `text`.
   A recorded test holds exact values: check data the test creates or data known to exist. Check a value the app
   makes up (a new order number, today's date) only when the start makes it predictable; otherwise check something
   else, or write a Java test.
6. **A step was wrong?** Do not patch it with more steps: `$UI recording '{"op":"redo","from":3}'` cuts step 3
   and later, replays the steps before it from a reset and records on. `delete {"n":3}` takes step 3's checks
   with it, `delete {"n":"3.1"}` only that check; `undo` takes back the last edit, yours or the person's.
7. **Stop and save:** `$UI recording '{"op":"stop"}'`, then
   `$UI export_recorded_test '{"name":"place-order","description":"A new order for Ada Shop Ltd"}'`, or a Java
   class with `export_test` (below). `recording '{"op":"export","as":"recorded","name":"place-order"}'` saves
   and keeps the recording.
8. **Verify** (below).

## Name the steps (when asked)

"Name the steps" or "describe the test": `$UI recording '{"op":"list"}'` first, then one edit per sentence,
each with the `rev` of the list the last edit returned:

```sh
$UI recording '{"op":"edit","n":2,"say":"Open a new order for Ada Shop Ltd","rev":14}'
$UI recording '{"op":"edit","n":"5.1","say":"The cart total is €31.32","rev":15}'
$UI recording '{"op":"group","from":3,"to":5,"say":"Fill the cart","rev":16}'
```

- A sentence is one short line in the person's words. It says what the step achieves, not which button it
  presses; a check reads as a fact. Never name a value that is not on the screen or in the step.
- `group` puts steps that belong together under one sentence (the same range again renames it);
  `{"op":"ungroup","n":4}` ends the group that holds step 4, its steps stay.
- `"say":""` removes a step's or check's sentence; a group always has one. A check-only step 1 has no sentence of
  its own: name its checks.
- Format and numbering: https://kormcrete.github.io/swing-ui-agent/reference/recorded-test-format/ and
  https://kormcrete.github.io/swing-ui-agent/guides/sentences/
- Sentences and groups are kept in the recorded test file and become comments in a Java test.

## When a person works with you

If `$UI session` shows `"palette": true` (the default; off with `palette=off` or `mode=background`), the person at
the app sees a palette in its window:

- **One recording, two authors.** A recording you start shows in the palette at once (Steps). The person can record
  too (● Record), add checks (Check), name steps (double click a row in Steps, or F2/Enter; also Describe… in the
  row menu), and delete, redo or undo; `list` shows who made each step. Pass the `rev` from your last `list`: an
  edit is refused when they changed the recording meanwhile, so list again.
- **They record, you save.** When they ask you to wait, `$UI recording '{"op":"list","wait":540000}'` returns once
  they press Done. You did not run their steps: `replay` before you trust them. `list`'s `notRecorded` also names
  what they did that no step can say (a tree's expand arrow, an entry that reads like another): add that step yourself.
- **Or they export.** After Done the palette offers Export…, which writes the file without you (a recorded test
  unless they choose Java test). When they ask you to check it, verify it as below. Later they open it again with
  Load recorded test… and ▶ Run it (Step by step to watch), fix it in Steps and save it back over the file.
- **Ask, don't guess.** When a target is unclear,
  `$UI inspect '{"op":"highlight","target":{...},"question":"Is this the Customer field?"}'` boxes every match in
  the app; the answer names the one they confirmed and its locator (`outcome` `confirmed` / `rejected`).
  `{"op":"pick","question":"Click the field that holds the customer"}` returns the locator of what they click.
- While a recording runs, `session reset` is refused: stop it first. With `"palette": false`, `inspect` answers
  CAPABILITY_GAP; work from the person's words.

## What is not recorded

- A `row` with more than two columns, a `loginAs` that is not the first step, and a `point` on anything but a plain
  left click: the step runs and the result says `notRecorded`. Pick another locator, or add the step to the class by
  hand. An `item` (a tree row or a list entry) is exported as `.item(...)`; a plain click at a `point` as `.clickAt(x, y)`.
- A `{"ref":"e12"}` target is recorded as a locator the recorder derives. `fragile:true` means it needed `nth`, or is
  a click at a `point`; find a better locator, reset and redo.
- Inside a `batch`, `{"verb":"screenshot","path":"..."}` writes a PNG and IS recorded; add `"scale":2` for a HiDPI
  (2x) image. To just look, call the `screenshot` tool.

## Keep it a regression test

- Prefer steps and checks that hold on every run: a row by a value the test created or one known to exist, counts
  with `gte`, values the test typed.
- Drive the controls a user would: the real button, the real menu item. Assert only what the user asked for.
- A check that something is absent or disabled also passes on the wrong screen. Pair it with a positive check in
  the same step: the New order tab is shown, then Checkout… is disabled.

## Export as a Java test

```sh
$UI export_test '{"class":"PlaceOrderUiIT"}'
```

`class` is required and ends in `UiIT`; `method` defaults to `recorded`; `package` to the plugin's export
package, else `recorded`; `clear` to true (refused while a `recording start` recording runs: stop it first). The
file goes under `-Dswingui.testsDir` of the app JVM, else `src/test/java` of the plugin's module or of the app's
working directory; `dir` (the test sources root, e.g. `<project>/src/test/java`) wins over both, and `warning` names the path when the app's working directory is no project; `savedTo` names the file. A refusal names each problem and keeps the recording: fix and retry. Read
`warning` (where the file went) and `startWarning` (reset and re-record with a `menu`/`press` first step).

Review the source. Rename the method if useful. Add by hand only checks no verb expresses, with the Client API
(`ui.describeModal()`, `ui.clipboardText()`, `UiAssertions.eventually(...)`) or the plugin's `ui.hooks(...)`.
Fix a wrong step in the recording before the export (step 6); after a clearing export, reset and record again
rather than hand-editing recorded steps.

## Verify

1. Put the app on some other screen (another menu item, another tab).
2. A recorded test: `$UI run_recorded_test '{"name":"place-order"}'` (the CLI exits 4 when it fails; the failing
   step carries what its locators match now and a picture under `evidence`). A Java test: run only the new class
   while the app is up, the project's way; without one `mvn -Dtest=PlaceOrderUiIT verify` (failsafe picks up `*IT`
   classes and filters with `-Dit.test=PlaceOrderUiIT`).
3. It must pass from there. On a Java failure read `target/ui-failures/<Class>/<method>/evidence.txt` and the PNG
   beside it; with the run report registered, `target/ui-runs/latest.txt` lists the run (`STARTED`, a
   `PASS`/`FAIL` line per test, `DONE`). Usually the start route is at fault: reset and re-record.

Report the file, the steps, the checks, and the verify result.
