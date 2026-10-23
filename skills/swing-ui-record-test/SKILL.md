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
  describes behaviour that is visible on screen, the way a tester or user would check it. It is for people showing
  the app, mostly not technical. It holds what they saw, and steps may use what an earlier step made (a new order's
  number), a remembered value or a text unique per run (see "Values from earlier steps" below).
- **Java `@UiTest`** when they ask for what a file cannot hold: a value computed from others or cut out of a longer
  text (`ORD-1640` out of "Order ORD-1640 placed"), loops, branches, or a technical check such as a database. It is
  for engineers working with you.
- **A test for a person stays a recorded test, whole.** Everything you build for them (links, sentences, groups,
  remembered values, unique texts) goes into the JSON file with the `recording` ops, and replays from that file
  alone: they never link or name a step, and the file is the only kind of test they have. Stay within what the file
  holds; if the test needs more, say so and ask whether they want a Java test.
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

An answering `session` means the agent is up, not that the app is ready. Wait for `ready: true` in `session` (the
plugin is ready and an app window shows), then `expect` the start screen shown: with a plugin, what its reset shows
(the Orders tab); without one, something the app enables once it is ready (a toolbar button `!disabled`).

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

A plugin may name the app's steps ("Add {product} to the cart") and checks ("The cart total is {total}"). They
come first, in this order:

1. **Read the vocabulary once per session**, before anything that drives or reads the app:
   `$UI vocabulary '{"op":"list"}'`. An empty list (with a hint) means there are none: use the raw verbs.
   `$UI vocabulary '{"op":"describe","step":"order-desk:addToCart"}'` shows a step's params with `choices` (the
   values that fit in the app right now): call it for a param you must choose.
2. **Map the whole request to steps and checks**, the checks included, with concrete values: the app's exact
   names. A word that is not one ("A4 paper") is resolved with `describe`'s `choices` before the batch (below).
   Search the whole list for every part before you call it uncovered, and read each step's `about`: questions,
   confirmations, messages and saved files often have steps of their own ("Ask to cancel the orders {orders},
   then keep them", "The exit question reads {message}"). `about` says what a step `Needs:` before it runs, where
   it `Ends:`, and the texts the app shows ("e.g. …").
3. **Run it as ONE `batch`**: the steps that get there, the action, the checks, and the steps that put the screen
   back (cancel the dialog, close the tab, clear the search: "Leave the screen as you found it" below), in that
   order, each step's `Needs:` where the one before it `Ends:`. A step is
   `{"verb":"do","step":"addToCart","params":{"product":"Gel pen, black"}}`, a check
   `{"verb":"do","step":"cartTotalIs","params":{"total":"€31.32"}}`, the screen put back
   `{"verb":"do","step":"closeTab"}` (alone: `$UI do_action
   '{"action":{"action":"do","step":"addToCart","params":{"product":"Gel pen, black"}}}'`). Make a second call only
   when the first stopped at a step that did not pass, or when a later step needs a value an earlier one makes: it
   comes back in the same call with `"outcome":true` on that step. The second call carries everything left, the
   closing steps too. A value an earlier step made is linked by the recorder (or with
   `link`), never typed as `${...}`. A domain check's param is never linked to what the check reads; read
   `recording list` `uses` to see what was. A step's own values (`codeWithOutcome` in the plugin) are under `step`
   in its outcome: `${placeOrder.step.number}`.
4. **Keep the batch going.**
   - **A value you are asked to tell** (a message, a reason, a count) and do not know: run the check that reads it
     with the text you expect, from the request or its `about`. When it fails, its `message` says what the app
     shows ("… reads “Account · Invoice 30 days”, expected “Account”"): that is the answer. A failed check records
     nothing, so the saved test must get it back with the shown text. One value: leave `continueOnFailedCheck`
     off; the next call begins with that check again with the shown text, then the rest.
     Several values: add `"continueOnFailedCheck":true` (a failed check is reported and the rest still runs; a
     failed act step still stops it), then put each failed check back with the shown text at the label it would
     have had (`recording list` shows the checks that were kept). An inserted check is not run; the re-runs prove
     it:
     ```sh
     $UI recording '{"op":"insert","at":"1.2","step":{"action":"do","step":"order-desk:orderItemsSummaryIs","params":{"order":"ORD-1639","summary":"55 units in 3 lines"}}}'
     ```
     Either way, no batch before the recording to learn the values. (The swing-ui-driver agent, when it only
     reports and saves nothing, needs neither: the message is its answer, and the batch goes on.)
   - **Something the app should refuse** (cancel a shipped order): run the check that reads the refusal (a button
     disabled, with its reason) instead of the act step that would fail.
   - **A value computed from another** ("the count goes up by one"): a recorded test cannot hold it. Check both
     values in the batch and tell the person, then `recording delete` those checks before you save (fixed values
     that held once fail on the next run), and say plainly that this check needs a Java test.
5. **Only for the parts no step covers**: find the targets with a scoped `read {"target":...}`, then raw verbs, in
   the same batch where you can (swing-ui-targeting). Never refuse a part because no step covers it, and never
   stretch a step to do something else. Never copy a locator from a step's `folds` text in the vocabulary list: it
   is pattern syntax, where `*` means any value; in a locator `*` is a literal, so no row matches and the step waits
   its whole timeout. In your report, one line per part done with raw verbs (any step that is not a `do`: a `menu`,
   `click`, `read`, `expect`, even one that worked at once), in exactly this form:
   `Plugin gap: no step or check for <the part, in their words>; done step by step instead.`

**No snapshot** to find the way or to report a result: not the `snapshot` tool, not a batch's `"snapshot"` option,
not a `snapshot` step. The step results (`passed`, `message`, `outcome`) and the checks are the report, and a
re-run of the saved test needs none either. A snapshot is the last resort, for a part no step covers whose target no
`read` finds (scoped: a `target` or `{"window":"dialog"}` and a `depth`; never unscoped, it is the whole app), and
each one gets its own `Plugin gap:` line.

- **Resolve loose values against the choices.** "the gel pens" is `Gel pen, black` when that is the only choice
  that fits. When two fit, ask the person; do not pick. A call may pass a value that is not among the choices, but
  say so.
- **A step may not fit every record** (an optional field with another caption). No separate try first: when a `do`
  step fails in the batch, read its result (below) and finish with raw verbs.
- **A `do` step that fails part way** leaves the app where it stopped. The steps of it that ran are recorded as
  plain steps, so the recording matches the screen: the result has `recordedPart` (the steps) and
  `recordedPartNote` (in words; for a tool error, the last line of the error's details). A step that cannot be
  recorded raw (a secret, no Java form) ends what is kept; the note names it and the `recording redo {"from":n}`
  that records from there again. When not even its first step can be kept, nothing is recorded (`recordedPart`
  is empty) and the note says the recording does not match the screen, with the `recording redo` that puts the app
  back where the recording ends. The kept steps stay raw (they never fold back into the step that failed). Finish
  the rest with raw verbs, or `redo` from the kept steps. A failed check records nothing.
- **Say which step you used** in your report, as the sentence with its values ("Add 10 × Gel pen, black to the
  cart"). Steps are named `<plugin>:<id>` in the recorded test, even with one plugin. A `do` step's own sentence
  follows its links: with the order number linked, "Open order ORD-1641" is saved as "Open order the number placeOrder
  made" (the run report's `action` shows each run's number). Write your own `say` for other words; it is kept.
- Raw steps that match a step fold into it as they are recorded (`recording list` shows `folded` and `raw`); a check,
  a `do` step, a group or sentence, or a change of author stops a fold. `fold {from,to}`, `unfold {n}` (the steps stay
  raw afterwards) and `keepRaw {from,to}` change it. A check step (`kind: check`) never folds by itself: run it
  with `do` where you would use `expect`.
- A recording that begins with a `do` step is a defined start when its steps open with a menu, a key press or
  `navigate`; otherwise start with one of those. A vocabulary-only plugin changes no reset, and `login`/`navigate`
  still need an app plugin.

## Steps

1. **Plan from the vocabulary; explore only the rest.** Where the app's own steps cover the request, there is
   nothing to find, and nothing to try first: record it at once, with no try-out batch before `recording start`,
   not even to learn a value you must tell (step 4 of "The app's own steps" learns it inside the recording). A
   wrong step is fixed with step 6. For the parts the steps do not cover, find the route and every target before
   recording: a scoped `read` (see the swing-ui-targeting skill; a snapshot only as the last resort above).
2. **Start at a reset:** `$UI recording '{"op":"start","reset":true}'`. (`session '{"op":"reset"}'` starts a
   recording too, but the palette does not show it.) Read `startNote` in the result: a search or filter left filled
   next to a table on the start screen.
3. **Reach the start screen** as the first step: `$UI menu '{"path":["File","New order"]}'`.
4. **Act.** Read each result: `passed`, `message`, `recorded` (the step as it will be saved), `notRecorded`
   (it ran but has no Java form, and why), `linkNote` (a value an earlier step made sits where no link can go: an
   `id:`/`text:` scope step or a column; it stays exact), `pending` (a dialog waits: `answer` it). Once the next
   steps are certain, send them as one `batch`; it stops at the first step that does not pass:
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
   $UI expect '{"target":{"scope":["tab:Orders"],"role":"table"},"rows":{"op":"eq","value":2}}'
   ```
   Check a value shown next to a caption (a total, a status) by that caption: `label 'Total'` + `text`.
   When the label next to the value is not tied to it (the caption itself answers to `label '<caption>'` and shows the caption), the value label is named by its own text: check it with `{"role":"label","name":"<value>"},"shown":true`. A per-run value there is linked through its `name` (and through a `tab:`/window title).
   Check data the test creates or data known to exist. A value the app makes up (a new order number) is fine in a
   LATER step: the recorder links it to the step that made it (see "Values from earlier steps"). A check of the very
   label, field or cell that showed the new value is never linked to that change of its own (it would compare it
   with itself). When the same value also came as a new row, a field elsewhere or a remembered value, the check IS
   linked to that, and works on every run (the order tab's title and label link to the new Orders row). Only a
   value shown nowhere else stays exact: remember it, or check something else.
6. **A step was wrong?** Do not patch it with more steps: `$UI recording '{"op":"redo","from":3}'` cuts step 3
   and later, replays the steps before it from a reset and records on. `delete {"n":3}` takes step 3's checks
   with it, `delete {"n":"3.1"}` only that check; `undo` takes back the last edit, yours or the person's.
7. **Leave the screen as you found it, then stop and save.** Before you stop, record the steps that put the screen
   back: clear what you typed into a search, put a filter back, close the tab. They are the test's own last steps,
   so every run ends where the next one starts; send them in the same batch as the rest, not in a call of their
   own. Then `$UI recording '{"op":"stop"}'`. Save with
   `$UI recording '{"op":"export","as":"recorded","name":"place-order"}'` while you still verify; it keeps the
   recording, so a fix is `insert`/`edit`/`redo`, `replay`, export again over the same file.
   `$UI export_recorded_test '{"name":"place-order","description":"A new order for Ada Shop Ltd"}'` empties it
   (unless `"clear":false`): use it last. After a clearing save, `recording '{"op":"load","name":"place-order"}'`
   brings the file back. A Java class is `export_test` (below).
8. **Verify** (below).

## Values from earlier steps

A recorded test is replayed again and again, and each run makes its own records (`ORD-1640`, then `ORD-1641`). A
step that uses what an earlier step made is LINKED: the file holds `${<id><path>}` and each run fills in that run's
value, or the step fails in words. Never the recorded value.

- **Automatic links.** While recording, a value you click, type, pick or check is linked when it is new in an
  earlier step's outcome, 4 characters or more, not yes/no/true/false, not a whole number below 1000, whole (or a
  whole part of typed text). A fresh value in a `tab:`/`section:` title or a `dialog:`/`window:` title is linked like a
  name (`window:${copy...}`); a value in an `id:`/`text:` scope step or a `column` stays exact and the step result
  says so in `linkNote`; a title with data next to fixed words stays a `*` glob. Most recent step first, and within
  one step a field, then a row, an item, a plugin step's own value, before a text; a row or item only when that step
  added (or changed) that one alone. Not linked: a title a step opened (tab, dialog, window: link it by hand when it
  is data), a value through a name made of the value itself (a label known by its own text; a check of such a label
  links its `name` to a value an earlier step made, never to text a step typed: make that text `unique` and the
  check follows), a check against its own element's new text, field or cell when nothing else holds the value (see
  step 5 above), a value in another form, rows from a search.
- **Look before you use a late value.** The recorder takes changes until your next action, so `read` or `expect`
  until the new row is there, then click it. A row you click before it appears is not linked.
- **Check what was linked:** `$UI recording '{"op":"list"}'` shows per step `changed` (path: value, what a later
  step can link to: the paths later steps use first, at most 20, never a row among several; `changedMore` counts the
  rest), `uses` [{from, value}], `usedBy` and `unique`, and on a remember entry `remembered` (the value
  it read).
- **Link by hand** when the rules do not fire but the value is surely the same (short values too; never a row among
  several a step added, nor a value in another form: those are refused):
  `$UI recording '{"op":"link","n":"7.1","ref":"${3.rows['Orders › table'].added[0].Order}"}'` (a step number or id, then a
  path from its `changed`; or a remembered name, `"ref":"orderNumber"`). `unlink {n}` turns links back into values.
  A step of a loaded file holds the value of the run it was recorded on: add `"field":"target.name"` (the place) and
  every place of the step with that value is linked. A path that names the value itself (`.texts['ORD-1641']`) is
  refused: link to a row, a field or a remembered value instead.
- **Remember** a value already on screen (not a change): `$UI recording
  '{"op":"remember","target":{"role":"table","scope":["tab:Orders"],"column":"Order"},"selected":true,"as":"orderNumber"}'`
  (a check-like entry under the last step, or under step `n`; refused when missing or empty). Later steps link to
  `${orderNumber}`. `{"role":"tabs"}` is AMBIGUOUS when a tab holds its own strip. Remember the value from a place
  that is unique instead (a table cell with `selected:true`, a field, a label); the main strip without an id is
  `{"role":"tabs","nth":0}` (counted from 0, in tree order; the step is `fragile`). The agent's `remember` is one
  edit: one `undo` takes it back. `move` cannot move a check: delete it and insert it where it belongs (the file
  form needs no `action`).
- **Unique per run** for typed text that must not repeat (a note, a new customer's name):
  `$UI recording '{"op":"unique","n":5,"where":"end"}'` adds `${run.id}` (8 letters and digits, new per run); later
  steps that use that text follow it. Nothing is typed again.
- **Never type `${...}` yourself.** What you type is taken as typed (a literal): steps you run, and the `text` and
  `target` of `recording edit`. Links come only from the recorder and `link`; an `edit` keeps a step's link where the
  new value is the one the link stood for (give the value, not the reference). A whole step you give `insert` or
  `edit {step}` is in the file's form (`${id.path}` there is a reference); an edit that leaves a reference without an
  EARLIER source, or an id twice, is refused and the recording stays as it was.
- **See what a step changed:** `"outcome": true` on `do_action`, `batch` steps or a verb tool returns
  `outcome` (only what changed, keyed by visible names) and `outcomeHandle`; `read {"form":"outcome","handle":N}`
  reads it again for a late change.
- **Prove it:** save, then `run_recorded_test` TWICE ON ONE APP START (no restart between): a test that leaves a
  search or filter filled passes the first time and fails the second. Each run must pass on its own new record (the
  report shows each resolved value, e.g. `(copy.rows['Orders › table'].added[0].Order = ORD-1642)`). The test's last
  steps leave the screen as they found it (clear what was typed into a search, put a filter back: step 7), and read
  `startNote` after a reset.
- More: https://kormcrete.github.io/swing-ui-agent/guides/values-from-earlier-steps/

## Name the steps

Name every step and check before you save a recorded test for a person, unasked: they should never have to. "Name
the steps" or "describe the test" asks for it too. `$UI recording '{"op":"list"}'` first, then one edit per
sentence, each with the `rev` of the list the last edit returned:

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
- A recorded locator keeps the tabs and titled sections around the target, except a strip of open documents
  (tabs that carry a component of their own, such as a close button): their titles are data.
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

Links export as code: a source step as `Outcome copy = ui.outcomeOf(() -> ...)` and its values as
`copy.text("$.rows['Orders › table'].added[0].Order")` (waits for a late value, up to the client's timeout), a remember entry as
`String orderNumber = ....selectedText()` / `.shownText()`, run values as `ui.run().id()`. A `do` step whose Java
form is a typed call or several steps cannot be a source; the export says so.

Review the source. Rename the method if useful. Add by hand only checks no verb expresses, with the Client API
(`ui.describeModal()`, `ui.clipboardText()`, `UiAssertions.eventually(...)`, `ui.outcomeOf(...)`) or the plugin's
`ui.hooks(...)`.
Fix a wrong step in the recording before the export (step 6); after a clearing export, reset and record again
rather than hand-editing recorded steps.

## Verify

1. Without a plugin reset, put the app on some other screen first (another menu item, another tab): the test must
   reach its start screen by itself. With a plugin whose reset brings a defined start, skip this: every run starts
   with that reset, so run the test straight after the save, with no call in between.
2. A recorded test: `$UI run_recorded_test '{"name":"place-order"}'` (the CLI exits 4 when it fails; the failing
   step carries what its locators match now and a picture under `evidence`). A Java test: run only the new class
   while the app is up, the project's way; without one `mvn -Dtest=PlaceOrderUiIT verify` (failsafe picks up `*IT`
   classes and filters with `-Dit.test=PlaceOrderUiIT`).
3. It must pass from there. On a Java failure read `target/ui-failures/<Class>/<method>/evidence.txt` and the PNG
   beside it; with the run report registered, `target/ui-runs/latest.txt` lists the run (`STARTED`, a
   `PASS`/`FAIL` line per test, `DONE`). Usually the start route is at fault: reset and re-record.

Report the file, the steps, the checks, the verify result, and the `Plugin gap:` lines (step 5 of "The app's own
steps").
