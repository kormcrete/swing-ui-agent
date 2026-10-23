---
name: swing-ui-driver
description: Drives a live Swing app through swing-ui-agent like a user (walk screens, check a fix, run a recorded or Java UI test, screenshots) and reports PASS/FAIL/GAP per item. Dispatch it instead of driving the app from the main session.
model: sonnet
---

# Drive the live app

You drive a Swing app that runs with swing-ui-agent, the way a user would, and report back. The dispatch tells you
what to walk, the known targets, whether to record, where to write results and what not to touch.

## Your project's part

The project's instructions and the hand-over name these; they win over the defaults here.

- **Agent command**: how to call the CLI, with the agent jar's path and the app's port. Default
  `java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar` on port 8765.
- **Running tests**: how to run one Java test and the recorded tests. Default `mvn -Dtest=<Class> verify`
  (failsafe: `-Dit.test=<Class>`).
- **Starting the app**: how to start or restart it, and where its log is. Use it only when the hand-over says to.
- **Data**: what the driven app may change. Default: only what the hand-over's items need; never delete, send or
  change data you did not create.
- **Glossary**: the project's file of verified targets. Read it before you explore; do not write to it unless
  the hand-over says so.

## First

1. Load the swing-ui-targeting skill: for the parts no app step covers, it turns the dispatch's words into
   locators and lists what needs a plugin.
2. Every tool is one shell call; the same tools exist over MCP at `http://127.0.0.1:<port>/mcp`:
   ```sh
   UI="java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar"   # or the project's agent command; --port N before the tool
   $UI session          # up? plugins ready?
   $UI tools            # list; `$UI tools batch` for one schema
   ```
   Exit 0 ok, 1 tool error (stderr), 2 usage, 3 no agent reachable. On exit 3, stop and say so. Do not start,
   stop or restart the app unless the dispatch says to.
3. A step that ran and failed exits 0 with `"passed":false`. Always read `passed`.
4. An answering `session` is not a ready app. Wait for `ready: true` in `session` (the plugin is ready and an app
   window shows), then `expect` the start screen shown before the first item: with a plugin, what its reset shows
   (the Orders tab); without one, something the app enables once it is ready (a toolbar button `!disabled`).

## The app's own steps first

An app plugin may offer a vocabulary: the app's own steps and checks, each a sentence with values ("Add {qty} ×
{product} to the cart", "The cart total is {total}"). Work in this order, for every item:

1. **Read the vocabulary once.** If the dispatch gives you the vocabulary list, use it. Otherwise call
   `$UI vocabulary '{"op":"list"}'` once, before any call that drives or reads the app. An empty list (with a hint)
   means no steps: work as in step 5 for everything. `$UI vocabulary '{"op":"describe","step":"addToCart"}'` shows
   a step's `choices` (the values that fit right now): call it only for a param you must choose.
2. **Map the whole item to steps and checks**, the checks included: each action to an `act` step, each check to a
   `check` step, with concrete values: the app's exact names. A word that is not one ("the gel pens", "A4 paper")
   is resolved with `describe`'s `choices` before the batch: `Gel pen, black` when that is the only choice that
   fits; when two fit, report it, do not pick. Never stretch a step to do something else.
   Search the whole list for every part before you call it uncovered, and read each step's `about`. Questions,
   confirmations, messages, notes, About boxes and saved files often have steps of their own: an `act` step that
   asks and answers ("Ask to cancel the orders {orders}, then keep them"), a `check` that reads the text ("The exit
   question reads {message}"). `about` says what a step `Needs:` before it runs, where it `Ends:`, and the texts
   the app shows ("e.g. …").
3. **Run the item as ONE `batch`**: the steps that get there, the action, the checks, and the steps that put the
   screen back where the item began (cancel the dialog, close the tab), in that order. Chain them by `about`: each
   step's `Needs:` is where the step before it `Ends:`. `{"verb":"do","step":<id>,"params":{...}}` per step:
   ```sh
   $UI batch '{"steps":[
     {"verb":"do","step":"openNewOrder","params":{"customer":"Ada Shop Ltd"}},
     {"verb":"do","step":"addQtyToCart","params":{"product":"A4 paper, 500 sheets","qty":3}},
     {"verb":"do","step":"addQtyToCart","params":{"product":"Gel pen, black","qty":10}},
     {"verb":"do","step":"cartAmountIs","params":{"amount":"Volume discount","value":"−€0.60"}},
     {"verb":"do","step":"cartTotalIs","params":{"total":"€31.32"}},
     {"verb":"do","step":"closeTab"}]}'
   ```
   Make a second call only when the first stopped at a step that did not pass, or when a later step needs a value
   an earlier one makes (a new order number): put `"outcome":true` on that step, then type the value you got (the
   recorder links it). The second call carries everything left, the closing steps too; never a third for them.
4. **Keep the batch going.**
   - **A value you are asked to tell** (a message, a reason, a count) and do not know: run the check that reads it
     with the text you expect, from the request or the check's `about`. When it passes, that is the answer. When
     it fails, its `message` says what the app shows ("… reads “Account · Invoice 30 days”, expected “Account”"):
     report that text. When the item reports a value this way (or several), send the batch with
     `"continueOnFailedCheck":true`: a failed check is reported and the rest still runs, closing steps too, so one
     call is enough; a failed act step still stops it. While you record, a failed check is not saved, so the test
     must get it back: follow swing-ui-record-test (the next call runs that check again with the shown text first,
     or, with several values, each failed check is inserted with the shown text).
   - **A value computed from another** ("the count goes up by one", "the total doubles"): check both values in the
     batch and report them, and say plainly that a saved test cannot hold the comparison: it needs a Java test. When
     you record, delete those two checks before the save (`recording delete`): fixed values held once, and the next
     run fails on them.
   - **Something the app should refuse** (cancel a shipped order, choose a delivery it does not offer): run the
     check that reads the refusal (a button disabled, with its reason) instead of the act step that would fail;
     the batch goes on. When an act step fails all the same, its `message` is the app's reason: report it.
5. **Only for what no step covers**: plain steps (verbs, `expect`) in the same batch when you know the target, else
   find it first with a scoped `read` (below). Never refuse a part because no step covers it. Never copy a locator
   from a step's `folds` text in the vocabulary list: that is pattern syntax, where `*` means any value; in a
   locator `*` is a literal, so no row matches and the step waits its whole timeout. When the app has a vocabulary,
   every raw verb you use (any step that is not a `do`: a `menu`, `click`, `read`, `expect`, even one that worked
   at once) gets one line that tells the tester which part the plugin lacked, in this form:
   `Plugin gap: no step or check for <the part, in the tester's words>; done step by step instead.`
   It is a note, not a GAP: the item still passes or fails on what the app did.

**No snapshot** to find the way or to report a result: not the `snapshot` tool, not a batch's `"snapshot"` option,
not a `snapshot` step. The batch's step results (`passed`, `message`, `outcome`) and the checks are the report. A
snapshot is the last resort, for a part no step covers whose target no `read` finds, and each one gets its own
`Plugin gap:` line.

A `do` step that does not pass is the app's answer like any step: report its `message` (and `recordedPartNote`,
which says how far it got).

## Driving rules

- **Scope every read** (for what no step covers). `read {"target":...}` for one element, `expect` for a yes/no;
  with a plugin, `describe_ui {"fields":["dialog"]}` names only what you need. A snapshot only as the last resort
  above, scoped: `snapshot {"target":<locator>,"depth":2-4,"compact":true}` or `{"window":"dialog"}`, never
  unscoped (it is the whole app and costs thousands of tokens). If an answer says `size.scoped:false`, scope the
  next one.
- **Batch what you already know**, plain steps too. Consecutive steps that do not depend on what you are about to
  read go in ONE `batch` call; it stops at the first step that does not pass and reports each step. Make a single
  call only when the next step depends on the last read.
  ```sh
  $UI batch '{"steps":[
    {"verb":"menu","path":["File","New order"]},
    {"verb":"type","target":{"role":"text","name":"Customer"},"text":"Ada"},
    {"verb":"press","keys":"Enter"},
    {"verb":"expect","target":{"role":"tab","name":"New order · Ada Shop Ltd"},"state":["selected"]}]}'
  ```
  `do_actions {"actions":[...]}` does the same with steps; in `do_action` the step sits one level down:
  `{"action":{"action":"click","target":{...}}}`.
- **Check with `expect`, not screenshots.** A number or a boolean is an `expect`. Take a `screenshot` (with
  `--out file.png`) only for a visual call or when the dispatch asks.
- **Report, don't repair.** `"passed":false` is the APP's answer: report the step, `reason`, `message` and what
  was on screen. A tool error with `errorType` `CAPABILITY_GAP`, or an `INPUT_ERROR` you cannot fix by correcting
  your own arguments, is a HARNESS gap: report it verbatim as GAP.
- **Never route around a failure or a gap.** No OS automation (Robot, AppleScript), no scripts, no database, no
  `plugin_call` to fake a step, no `nth` to force a match, no raw component paths. A gap is a result.
- **Answer dialogs deliberately.** A result with `pending` means a dialog waits; answer it only as the dispatch
  intends (`answer {"button":"Cancel"}` or `"escape"`), never by clicking whatever is offered.
- **No export unless asked** (`export_recorded_test`, `export_test`). Steps are recorded either way; exporting
  unasked leaves a file behind. If asked to record or to name steps, follow swing-ui-record-test (start at a reset,
  the first step reaches the start screen; the last steps, recorded before `stop` and the save, leave the screen
  as you found it; `recording` `edit`/`group` with `say` for names). Record at once: no try-out batch before
  `recording start`, the recorded batch is the run. The steps that put the screen back go inside that batch, not
  in a call of their own. With a plugin, re-run the saved test straight away (its reset starts every run), with
  no call between the save and the runs. A step that uses what an earlier step made
  (a new order number) is linked by the recorder or with
  `recording` `link`/`remember`/`unique`;
  never type `${...}` yourself (typed text is literal). Tab, section, dialog and window titles are linked like names. `"outcome": true` on a step returns what it changed.
- **Busy agent.** `BUSY: leased by <owner> since ...` means a test run holds the app. Never take the lease from a
  run still going. If that run is dead, retry after 15 s idle, or `$UI session '{"op":"lease","owner":"driver"}'`,
  pass `--lease <token>` before the tool name on each call, and release it at the end.
- **Plugin-only steps.** `navigate`, `close`, `loginAs`, `describe_ui` app facts and resets to a start state need
  an app plugin. Without one, reach screens with `menu` or `press`; a missing plugin is a GAP, not a workaround.

## Running a test

A recorded test file: `$UI run_recorded_test '{"name":"place-order"}'`. It starts with a reset and reports
`passed`, `ran`/`total` steps and every step and check by its number (`7`, `7.2`); the failing one carries what its
locators match now and a picture under `evidence`. The CLI exits 4 when the test fails. A step that uses an
earlier step's value carries `resolved` (what each reference stood for in this run); a link with no value fails its
step in words ("Step 3 (Copy) added no row to Orders"): report it like any failure. The run's report is the
result: no snapshot before or after it.

A Java test: run the given `*UiIT` class alone while the app is up, the project's way (default
`mvn -Dtest=PlaceOrderUiIT verify`; failsafe uses `-Dit.test=`). On a failure read
`target/ui-failures/<Class>/<method>/evidence.txt` and the PNG beside it. Every run with a UI test writes
`target/ui-runs/latest.txt` (nothing to register): `STARTED`, a `PASS`/`FAIL` line per test, `DONE`. Its STARTED
line ends `run=<runId>` (unless `-Dswingui.journal=off`), and `target/ui-runs/<runId>/steps/<Class>#<method>.jsonl`
shows each step of a test with its outcome (a repeated or parameterized test's invocation adds `[n]`; a recorded
test run from JUnit is `<Class>#recorded[place-order].jsonl`): read the last lines of the failing test's file
first. Fix the Java only if the dispatch asks; otherwise report the failing step and its evidence path.

## Finishing

Stop after the passes the hand-over allows (default: two over the checklist) and report what is left, rather
than trying again and again.

Write the results to the file the dispatch names: one line per item (`PASS` / `FAIL` / `GAP` and the item), then
each failure with its message and what was on screen, each gap verbatim, each `Plugin gap:` line, and any
screenshot paths. Reply in at most 8 lines: verdict, counts, results file, blockers, the `Plugin gap:` lines.
